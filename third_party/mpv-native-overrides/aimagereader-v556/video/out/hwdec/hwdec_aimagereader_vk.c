/*
 * Android AImageReader / Vulkan interop.
 *
 * MediaCodec surface output is normally backed by an opaque AHardwareBuffer.
 * Import it into Vulkan and convert it with the driver's YCbCr conversion
 * sampler into a regular RGB image that libplacebo can consume. This keeps the
 * whole path on the GPU and avoids mediacodec-copy's CPU surface download.
 */

#include "config.h"

#include <inttypes.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

#include <libplacebo/vulkan.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include "common/common.h"
#include "common/msg.h"
#include "video/out/gpu/context.h"
#include "video/out/gpu/hwdec.h"
#include "video/out/placebo/ra_pl.h"
#include "video/out/vulkan/context.h"

#include "hwdec_aimagereader_comp.h"
#include "hwdec_aimagereader_vk.h"

#define OUTPUT_COUNT 3
#define INPUT_CACHE_SIZE 8
#define EXTERNAL_FORMAT_DESCRIPTOR_COUNT 4

struct vk_input {
    AHardwareBuffer *buffer;
    VkImage image;
    VkDeviceMemory memory;
    VkImageView view;
    uint64_t last_used;
    int users;
    bool initialized;
};

struct vk_output {
    VkImage image;
    VkDeviceMemory memory;
    VkImageView view;
    pl_tex pltex;
    struct ra_tex *ratex;
    VkDescriptorSet descriptor;
    VkCommandBuffer command;
    VkSemaphore available;
    VkSemaphore acquire;
    VkSemaphore ready;
    VkFence fence;
    VkQueryPool query_pool;
    AImage *source_image;
    struct mp_image *source_frame;
    struct vk_input *input;
    bool pending;
    bool written;
    bool released;
    bool timing_pending;
};

struct aimagereader_vk {
    struct mp_log *log;
    struct ra_hwdec_mapper *mapper;
    struct aimagereader_vk_api api;

    pl_gpu gpu;
    pl_vulkan vk;
    VkDevice device;
    VkQueue queue;
    uint32_t queue_family;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID GetAHBProperties;
    PFN_vkGetPhysicalDeviceImageFormatProperties2 GetImageFormatProperties2;
    PFN_vkImportSemaphoreFdKHR ImportSemaphoreFdKHR;
    float timestamp_period;

    VkCommandPool command_pool;
    VkFormat output_format;
    int width;
    int height;
    int output_index;
    bool resources_ready;

    uint32_t source_ahb_format;
    VkFormat source_format;
    uint64_t external_format;
    VkAndroidHardwareBufferFormatPropertiesANDROID source_props;
    VkSamplerYcbcrConversion conversion;
    VkSampler sampler;
    VkDescriptorSetLayout descriptor_layout;
    VkDescriptorPool descriptor_pool;
    VkPipelineLayout pipeline_layout;
    VkPipeline pipeline;

    struct vk_input inputs[INPUT_CACHE_SIZE];
    int num_inputs;
    uint64_t input_serial;
    struct vk_output outputs[OUTPUT_COUNT];
};

static bool vk_success(struct aimagereader_vk *p, VkResult result,
                       const char *operation)
{
    if (result == VK_SUCCESS)
        return true;
    mp_err(p->log, "%s failed: %d\n", operation, result);
    return false;
}

static bool has_extension(pl_vulkan vk, const char *name)
{
    for (int n = 0; n < vk->num_extensions; n++) {
        if (strcmp(vk->extensions[n], name) == 0)
            return true;
    }
    return false;
}

static bool can_import_sync_fd(pl_vulkan vk)
{
    PFN_vkGetPhysicalDeviceExternalSemaphoreProperties get_properties =
        (PFN_vkGetPhysicalDeviceExternalSemaphoreProperties)
        vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceExternalSemaphoreProperties");
    if (!get_properties) {
        get_properties = (PFN_vkGetPhysicalDeviceExternalSemaphoreProperties)
            vk->get_proc_addr(
                vk->instance,
                "vkGetPhysicalDeviceExternalSemaphorePropertiesKHR");
    }
    if (!get_properties)
        return false;

    VkPhysicalDeviceExternalSemaphoreInfo info = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO,
        .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
    };
    VkExternalSemaphoreProperties properties = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES,
    };
    get_properties(vk->phys_device, &info, &properties);
    return properties.externalSemaphoreFeatures &
           VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT;
}

static bool has_ycbcr_conversion(pl_vulkan vk)
{
    const VkStructureType ycbcr_type =
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES;
    const VkBaseOutStructure *feature = vk->features->pNext;
    while (feature) {
        if (feature->sType ==
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES) {
            const VkPhysicalDeviceVulkan11Features *vk11 =
                (const void *)feature;
            if (vk11->samplerYcbcrConversion)
                return true;
        }
        if (feature->sType == ycbcr_type) {
            const VkPhysicalDeviceSamplerYcbcrConversionFeatures *ycbcr =
                (const void *)feature;
            if (ycbcr->samplerYcbcrConversion)
                return true;
        }
        feature = feature->pNext;
    }
    return false;
}

static PFN_vkGetPhysicalDeviceImageFormatProperties2
get_image_format_properties2(pl_vulkan vk)
{
    PFN_vkGetPhysicalDeviceImageFormatProperties2 get_properties =
        (PFN_vkGetPhysicalDeviceImageFormatProperties2)
        vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceImageFormatProperties2");
    if (!get_properties) {
        get_properties =
            (PFN_vkGetPhysicalDeviceImageFormatProperties2)
            vk->get_proc_addr(
                vk->instance,
                "vkGetPhysicalDeviceImageFormatProperties2KHR");
    }
    return get_properties;
}

bool aimagereader_vk_available(struct ra_ctx *ra_ctx, struct mp_log *log)
{
    struct mpvk_ctx *ctx = ra_vk_ctx_get(ra_ctx);
    pl_gpu gpu = ra_pl_get(ra_ctx->ra);
    pl_vulkan vk = gpu ? pl_vulkan_get(gpu) : NULL;
    if (!ctx || !vk || !vk->get_proc_addr || !vk->features ||
        !vk->queue_compute.count ||
        !vk->features->features.shaderStorageImageWriteWithoutFormat ||
        !has_ycbcr_conversion(vk))
        return false;

    const char *ahb_extension =
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME;
    const char *foreign_extension =
        VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME;
    if (!has_extension(vk, ahb_extension) ||
        !has_extension(vk, foreign_extension)) {
        mp_verbose(log, "Vulkan device lacks Android hardware-buffer interop\n");
        return false;
    }

    PFN_vkGetDeviceProcAddr get_device_proc =
        (PFN_vkGetDeviceProcAddr)vk->get_proc_addr(vk->instance,
                                                   "vkGetDeviceProcAddr");
    return get_device_proc &&
           get_device_proc(vk->device,
                           "vkGetAndroidHardwareBufferPropertiesANDROID") &&
           get_image_format_properties2(vk);
}

static uint32_t find_memory_type(struct aimagereader_vk *p,
                                 uint32_t type_bits,
                                 VkMemoryPropertyFlags preferred)
{
    VkPhysicalDeviceMemoryProperties props;
    vkGetPhysicalDeviceMemoryProperties(p->vk->phys_device, &props);

    for (uint32_t n = 0; n < props.memoryTypeCount; n++) {
        if ((type_bits & (1u << n)) &&
            (props.memoryTypes[n].propertyFlags & preferred) == preferred)
            return n;
    }
    for (uint32_t n = 0; n < props.memoryTypeCount; n++) {
        if (type_bits & (1u << n))
            return n;
    }
    return UINT32_MAX;
}

static void destroy_input(struct aimagereader_vk *p, struct vk_input *input)
{
    if (input->view)
        vkDestroyImageView(p->device, input->view, NULL);
    if (input->image)
        vkDestroyImage(p->device, input->image, NULL);
    if (input->memory)
        vkFreeMemory(p->device, input->memory, NULL);
    *input = (struct vk_input){0};
}

static bool finish_output(struct aimagereader_vk *p, struct vk_output *output,
                          bool wait)
{
    if (!output->pending)
        return true;

    VkResult result = wait
        ? vkWaitForFences(p->device, 1, &output->fence, VK_TRUE, UINT64_MAX)
        : vkGetFenceStatus(p->device, output->fence);
    if (result == VK_NOT_READY)
        return false;
    if (!vk_success(p, result, "waiting for AHardwareBuffer conversion"))
        return false;

    if (output->source_image) {
        p->api.AImage_delete(output->source_image);
        output->source_image = NULL;
    }
    if (output->timing_pending) {
        uint64_t timestamps[2] = {0};
        VkResult timing = vkGetQueryPoolResults(
            p->device, output->query_pool, 0, 2, sizeof(timestamps), timestamps,
            sizeof(timestamps[0]), VK_QUERY_RESULT_64_BIT);
        if (timing == VK_SUCCESS && timestamps[1] >= timestamps[0]) {
            double elapsed = (timestamps[1] - timestamps[0])
                * (double)p->timestamp_period;
            p->mapper->external_gpu_time_ns = elapsed >= (double)INT64_MAX
                ? INT64_MAX : (uint64_t)(elapsed + 0.5);
            p->mapper->external_gpu_time_serial++;
        }
        output->timing_pending = false;
    }
    if (output->input) {
        if (output->input->users > 0)
            output->input->users--;
        output->input = NULL;
    }
    output->pending = false;
    return vk_success(p, vkResetFences(p->device, 1, &output->fence),
                      "resetting conversion fence");
}

static void destroy_output(struct aimagereader_vk *p,
                           struct vk_output *output)
{
    finish_output(p, output, true);
    mp_image_unrefp(&output->source_frame);

    if (output->ratex)
        ra_tex_free(p->mapper->ra, &output->ratex);
    if (output->view)
        vkDestroyImageView(p->device, output->view, NULL);
    if (output->image)
        vkDestroyImage(p->device, output->image, NULL);
    if (output->memory)
        vkFreeMemory(p->device, output->memory, NULL);
    if (output->available)
        vkDestroySemaphore(p->device, output->available, NULL);
    if (output->acquire)
        vkDestroySemaphore(p->device, output->acquire, NULL);
    if (output->ready)
        vkDestroySemaphore(p->device, output->ready, NULL);
    if (output->fence)
        vkDestroyFence(p->device, output->fence, NULL);
    if (output->query_pool)
        vkDestroyQueryPool(p->device, output->query_pool, NULL);
    *output = (struct vk_output){0};
}

static void destroy_conversion_resources(struct aimagereader_vk *p)
{
    for (int n = 0; n < OUTPUT_COUNT; n++)
        finish_output(p, &p->outputs[n], true);

    if (p->descriptor_pool)
        vkDestroyDescriptorPool(p->device, p->descriptor_pool, NULL);
    p->descriptor_pool = VK_NULL_HANDLE;

    VkCommandBuffer commands[OUTPUT_COUNT];
    uint32_t num_commands = 0;
    for (int n = 0; n < OUTPUT_COUNT; n++) {
        struct vk_output *output = &p->outputs[n];
        output->descriptor = VK_NULL_HANDLE;
        if (output->command)
            commands[num_commands++] = output->command;
        output->command = VK_NULL_HANDLE;
    }
    if (num_commands) {
        vkFreeCommandBuffers(p->device, p->command_pool, num_commands,
                             commands);
    }

    p->mapper->tex[0] = NULL;
    for (int n = 0; n < OUTPUT_COUNT; n++)
        destroy_output(p, &p->outputs[n]);

    for (int n = 0; n < p->num_inputs; n++)
        destroy_input(p, &p->inputs[n]);
    p->num_inputs = 0;

    if (p->pipeline)
        vkDestroyPipeline(p->device, p->pipeline, NULL);
    if (p->pipeline_layout)
        vkDestroyPipelineLayout(p->device, p->pipeline_layout, NULL);
    if (p->descriptor_layout)
        vkDestroyDescriptorSetLayout(p->device, p->descriptor_layout, NULL);
    if (p->sampler)
        vkDestroySampler(p->device, p->sampler, NULL);
    if (p->conversion)
        vkDestroySamplerYcbcrConversion(p->device, p->conversion, NULL);

    p->pipeline = VK_NULL_HANDLE;
    p->pipeline_layout = VK_NULL_HANDLE;
    p->descriptor_layout = VK_NULL_HANDLE;
    p->sampler = VK_NULL_HANDLE;
    p->conversion = VK_NULL_HANDLE;
    p->output_format = VK_FORMAT_UNDEFINED;
    p->width = 0;
    p->height = 0;
    p->output_index = -1;
    p->source_ahb_format = 0;
    p->source_format = VK_FORMAT_UNDEFINED;
    p->external_format = 0;
    p->source_props =
        (VkAndroidHardwareBufferFormatPropertiesANDROID){0};
    p->resources_ready = false;
}

static int ycbcr_format_depth(VkFormat format)
{
    switch (format) {
    case VK_FORMAT_G8B8G8R8_422_UNORM:
    case VK_FORMAT_B8G8R8G8_422_UNORM:
    case VK_FORMAT_G8_B8_R8_3PLANE_420_UNORM:
    case VK_FORMAT_G8_B8R8_2PLANE_420_UNORM:
    case VK_FORMAT_G8_B8_R8_3PLANE_422_UNORM:
    case VK_FORMAT_G8_B8R8_2PLANE_422_UNORM:
    case VK_FORMAT_G8_B8_R8_3PLANE_444_UNORM:
        return 8;
    case VK_FORMAT_G10X6B10X6G10X6R10X6_422_UNORM_4PACK16:
    case VK_FORMAT_B10X6G10X6R10X6G10X6_422_UNORM_4PACK16:
    case VK_FORMAT_G10X6_B10X6_R10X6_3PLANE_420_UNORM_3PACK16:
    case VK_FORMAT_G10X6_B10X6R10X6_2PLANE_420_UNORM_3PACK16:
    case VK_FORMAT_G10X6_B10X6_R10X6_3PLANE_422_UNORM_3PACK16:
    case VK_FORMAT_G10X6_B10X6R10X6_2PLANE_422_UNORM_3PACK16:
    case VK_FORMAT_G10X6_B10X6_R10X6_3PLANE_444_UNORM_3PACK16:
        return 10;
    case VK_FORMAT_G12X4B12X4G12X4R12X4_422_UNORM_4PACK16:
    case VK_FORMAT_B12X4G12X4R12X4G12X4_422_UNORM_4PACK16:
    case VK_FORMAT_G12X4_B12X4_R12X4_3PLANE_420_UNORM_3PACK16:
    case VK_FORMAT_G12X4_B12X4R12X4_2PLANE_420_UNORM_3PACK16:
    case VK_FORMAT_G12X4_B12X4_R12X4_3PLANE_422_UNORM_3PACK16:
    case VK_FORMAT_G12X4_B12X4R12X4_2PLANE_422_UNORM_3PACK16:
    case VK_FORMAT_G12X4_B12X4_R12X4_3PLANE_444_UNORM_3PACK16:
        return 12;
    case VK_FORMAT_G16B16G16R16_422_UNORM:
    case VK_FORMAT_B16G16R16G16_422_UNORM:
    case VK_FORMAT_G16_B16_R16_3PLANE_420_UNORM:
    case VK_FORMAT_G16_B16R16_2PLANE_420_UNORM:
    case VK_FORMAT_G16_B16_R16_3PLANE_422_UNORM:
    case VK_FORMAT_G16_B16R16_2PLANE_422_UNORM:
    case VK_FORMAT_G16_B16_R16_3PLANE_444_UNORM:
        return 16;
    default:
        return 0;
    }
}

static bool source_is_high_depth(
    struct aimagereader_vk *p, const AHardwareBuffer_Desc *desc,
    const VkAndroidHardwareBufferFormatPropertiesANDROID *props)
{
    const struct pl_bit_encoding *bits =
        &p->mapper->src_params.repr.bits;
    if (bits->color_depth > 8 || bits->sample_depth > 8 ||
        ycbcr_format_depth(props->format) > 8) {
        return true;
    }

    switch (desc->format) {
    case AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT:
    case AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM:
    case AHARDWAREBUFFER_FORMAT_YCbCr_P010:
        return true;
    default:
        return false;
    }
}

static VkFormat choose_output_format(
    struct aimagereader_vk *p, const AHardwareBuffer_Desc *desc,
    const VkAndroidHardwareBufferFormatPropertiesANDROID *props)
{
    bool high_depth = source_is_high_depth(p, desc, props);
    const VkFormat high_depth_candidates[] = {
        VK_FORMAT_A2B10G10R10_UNORM_PACK32,
        VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_FORMAT_R8G8B8A8_UNORM,
    };
    const VkFormat low_depth_candidates[] = {
        VK_FORMAT_R8G8B8A8_UNORM,
        VK_FORMAT_A2B10G10R10_UNORM_PACK32,
        VK_FORMAT_R16G16B16A16_SFLOAT,
    };
    const VkFormat *candidates =
        high_depth ? high_depth_candidates : low_depth_candidates;
    int num_candidates = MP_ARRAY_SIZE(high_depth_candidates);
    const VkFormatFeatureFlags needed =
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
        VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;

    for (int n = 0; n < num_candidates; n++) {
        VkFormatProperties format_props;
        vkGetPhysicalDeviceFormatProperties(p->vk->phys_device,
                                            candidates[n], &format_props);
        if ((format_props.optimalTilingFeatures & needed) == needed)
            return candidates[n];
    }
    return VK_FORMAT_UNDEFINED;
}

static int collect_queue_families(struct aimagereader_vk *p,
                                  uint32_t families[3])
{
    const struct pl_vulkan_queue queues[] = {
        p->vk->queue_graphics,
        p->vk->queue_compute,
        p->vk->queue_transfer,
    };
    int count = 0;
    for (int n = 0; n < (int)MP_ARRAY_SIZE(queues); n++) {
        if (!queues[n].count)
            continue;
        bool duplicate = false;
        for (int i = 0; i < count; i++)
            duplicate |= families[i] == queues[n].index;
        if (!duplicate)
            families[count++] = queues[n].index;
    }
    return count;
}

static bool create_output_image(struct aimagereader_vk *p,
                                struct vk_output *output,
                                const uint32_t *queue_families,
                                int num_queue_families)
{
    const VkImageUsageFlags usage =
        VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
    VkImageCreateInfo image_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = p->output_format,
        .extent = {p->width, p->height, 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = usage,
        .sharingMode = num_queue_families > 1
            ? VK_SHARING_MODE_CONCURRENT
            : VK_SHARING_MODE_EXCLUSIVE,
        .queueFamilyIndexCount =
            num_queue_families > 1 ? num_queue_families : 0,
        .pQueueFamilyIndices =
            num_queue_families > 1 ? queue_families : NULL,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    if (!vk_success(p, vkCreateImage(p->device, &image_info, NULL,
                                     &output->image),
                    "creating conversion output image"))
        return false;

    VkMemoryRequirements requirements;
    vkGetImageMemoryRequirements(p->device, output->image, &requirements);
    uint32_t memory_type = find_memory_type(
        p, requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memory_type == UINT32_MAX) {
        mp_err(p->log, "No memory type for conversion output image\n");
        return false;
    }

    VkMemoryAllocateInfo alloc_info = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = requirements.size,
        .memoryTypeIndex = memory_type,
    };
    if (!vk_success(p, vkAllocateMemory(p->device, &alloc_info, NULL,
                                        &output->memory),
                    "allocating conversion output memory") ||
        !vk_success(p, vkBindImageMemory(p->device, output->image,
                                         output->memory, 0),
                    "binding conversion output memory"))
        return false;

    VkImageViewCreateInfo view_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = output->image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = p->output_format,
        .subresourceRange = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .levelCount = 1,
            .layerCount = 1,
        },
    };
    if (!vk_success(p, vkCreateImageView(p->device, &view_info, NULL,
                                         &output->view),
                    "creating conversion output view"))
        return false;

    output->pltex = pl_vulkan_wrap(p->gpu, pl_vulkan_wrap_params(
        .image = output->image,
        .width = p->width,
        .height = p->height,
        .format = p->output_format,
        .usage = usage,
    ));
    if (!output->pltex) {
        mp_err(p->log, "libplacebo cannot wrap conversion output format %d\n",
               p->output_format);
        return false;
    }

    output->ratex = talloc_ptrtype(NULL, output->ratex);
    if (!mppl_wrap_tex(p->mapper->ra, output->pltex, output->ratex)) {
        pl_tex_destroy(p->gpu, &output->pltex);
        TA_FREEP(&output->ratex);
        return false;
    }

    VkSemaphoreCreateInfo semaphore_info = {
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
    };
    VkFenceCreateInfo fence_info = {
        .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
    };
    if (!vk_success(p, vkCreateSemaphore(p->device, &semaphore_info, NULL,
                                         &output->available),
                    "creating conversion availability semaphore") ||
        !vk_success(p, vkCreateSemaphore(p->device, &semaphore_info, NULL,
                                         &output->acquire),
                    "creating AImage acquire semaphore") ||
        !vk_success(p, vkCreateSemaphore(p->device, &semaphore_info, NULL,
                                         &output->ready),
                    "creating conversion completion semaphore") ||
        !vk_success(p, vkCreateFence(p->device, &fence_info, NULL,
                                     &output->fence),
                    "creating conversion fence"))
        return false;

    VkQueryPoolCreateInfo query_info = {
        .sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO,
        .queryType = VK_QUERY_TYPE_TIMESTAMP,
        .queryCount = 2,
    };
    if (!vk_success(p, vkCreateQueryPool(p->device, &query_info, NULL,
                                         &output->query_pool),
                    "creating conversion timestamp pool"))
        return false;

    return true;
}

static bool create_outputs(
    struct aimagereader_vk *p, const AHardwareBuffer_Desc *desc,
    const VkAndroidHardwareBufferFormatPropertiesANDROID *props)
{
    p->width = desc->width;
    p->height = desc->height;
    p->source_ahb_format = desc->format;
    p->output_format = choose_output_format(p, desc, props);
    if (p->output_format == VK_FORMAT_UNDEFINED) {
        mp_err(p->log, "No sampleable storage image format for AHardwareBuffer "
                       "conversion\n");
        return false;
    }

    uint32_t queue_families[3];
    int num_queue_families = collect_queue_families(p, queue_families);
    for (int n = 0; n < OUTPUT_COUNT; n++) {
        if (!create_output_image(p, &p->outputs[n], queue_families,
                                 num_queue_families))
            return false;
    }

    VkCommandBufferAllocateInfo alloc_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = p->command_pool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = OUTPUT_COUNT,
    };
    VkCommandBuffer commands[OUTPUT_COUNT];
    if (!vk_success(p, vkAllocateCommandBuffers(p->device, &alloc_info,
                                                commands),
                    "allocating conversion command buffers"))
        return false;
    for (int n = 0; n < OUTPUT_COUNT; n++)
        p->outputs[n].command = commands[n];

    return true;
}

static bool same_source_format(
    struct aimagereader_vk *p, const AHardwareBuffer_Desc *desc,
    const VkAndroidHardwareBufferFormatPropertiesANDROID *props)
{
    return p->source_ahb_format == desc->format &&
           p->source_format == props->format &&
           p->external_format == props->externalFormat &&
           p->source_props.formatFeatures == props->formatFeatures &&
           p->source_props.suggestedYcbcrModel ==
               props->suggestedYcbcrModel &&
           p->source_props.suggestedYcbcrRange ==
               props->suggestedYcbcrRange &&
           p->source_props.suggestedXChromaOffset ==
               props->suggestedXChromaOffset &&
           p->source_props.suggestedYChromaOffset ==
               props->suggestedYChromaOffset &&
           memcmp(&p->source_props.samplerYcbcrConversionComponents,
                   &props->samplerYcbcrConversionComponents,
                   sizeof(props->samplerYcbcrConversionComponents)) == 0;
}

static uint32_t sampler_descriptor_count(
    struct aimagereader_vk *p,
    const VkAndroidHardwareBufferFormatPropertiesANDROID *props,
    bool needs_conversion)
{
    if (!needs_conversion)
        return 1;

    // Vulkan has no query for opaque Android external formats. Four is the
    // conservative fallback also used by Android's ANGLE Vulkan backend.
    if (props->externalFormat)
        return EXTERNAL_FORMAT_DESCRIPTOR_COUNT;

    VkPhysicalDeviceImageFormatInfo2 format_info = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2,
        .format = props->format,
        .type = VK_IMAGE_TYPE_2D,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT,
    };
    VkSamplerYcbcrConversionImageFormatProperties ycbcr_props = {
        .sType =
            VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_IMAGE_FORMAT_PROPERTIES,
    };
    VkImageFormatProperties2 image_props = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2,
        .pNext = &ycbcr_props,
    };
    if (!vk_success(p, p->GetImageFormatProperties2(
                           p->vk->phys_device, &format_info, &image_props),
                    "querying YCbCr sampler descriptor count")) {
        return 0;
    }

    return ycbcr_props.combinedImageSamplerDescriptorCount;
}

static bool create_pipeline(
    struct aimagereader_vk *p,
    const VkAndroidHardwareBufferFormatPropertiesANDROID *props)
{
    p->source_format = props->format;
    p->external_format = props->externalFormat;
    p->source_props = *props;
    p->source_props.pNext = NULL;

    bool needs_conversion = props->externalFormat ||
                            ycbcr_format_depth(props->format);
    uint32_t sampler_descriptors =
        sampler_descriptor_count(p, props, needs_conversion);
    if (!sampler_descriptors)
        return false;

    VkFilter chroma_filter = VK_FILTER_NEAREST;
    VkFilter sample_filter = VK_FILTER_NEAREST;
    bool linear_chroma =
        props->formatFeatures &
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER_BIT;
    bool linear_sample =
        props->formatFeatures &
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    bool separate_filter =
        props->formatFeatures &
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_SEPARATE_RECONSTRUCTION_FILTER_BIT;
    if (needs_conversion && linear_chroma) {
        chroma_filter = VK_FILTER_LINEAR;
        if (linear_sample) {
            sample_filter = VK_FILTER_LINEAR;
        } else if (!separate_filter) {
            chroma_filter = VK_FILTER_NEAREST;
        }
    }
    VkExternalFormatANDROID external_format = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID,
        .externalFormat = props->externalFormat,
    };
    if (needs_conversion) {
        VkSamplerYcbcrConversionCreateInfo conversion_info = {
            .sType =
                VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO,
            .pNext = props->externalFormat ? &external_format : NULL,
            .format = props->externalFormat
                ? VK_FORMAT_UNDEFINED
                : props->format,
            .ycbcrModel = props->suggestedYcbcrModel,
            .ycbcrRange = props->suggestedYcbcrRange,
            .components = props->samplerYcbcrConversionComponents,
            .xChromaOffset = props->suggestedXChromaOffset,
            .yChromaOffset = props->suggestedYChromaOffset,
            .chromaFilter = chroma_filter,
        };
        if (!vk_success(p, vkCreateSamplerYcbcrConversion(
                            p->device, &conversion_info, NULL, &p->conversion),
                        "creating Android YCbCr conversion"))
            return false;
    }

    VkSamplerYcbcrConversionInfo conversion_info = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO,
        .conversion = p->conversion,
    };
    VkSamplerCreateInfo sampler_info = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .pNext = p->conversion ? &conversion_info : NULL,
        .magFilter = sample_filter,
        .minFilter = sample_filter,
        .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
        .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .maxLod = 0.0f,
    };
    if (!vk_success(p, vkCreateSampler(p->device, &sampler_info, NULL,
                                       &p->sampler),
                    "creating Android hardware-buffer sampler"))
        return false;

    VkDescriptorSetLayoutBinding bindings[] = {
        {
            .binding = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
            .pImmutableSamplers = &p->sampler,
        }, {
            .binding = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        },
    };
    VkDescriptorSetLayoutCreateInfo descriptor_layout_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = MP_ARRAY_SIZE(bindings),
        .pBindings = bindings,
    };
    if (!vk_success(p, vkCreateDescriptorSetLayout(
                        p->device, &descriptor_layout_info, NULL,
                        &p->descriptor_layout),
                    "creating conversion descriptor layout"))
        return false;

    VkPipelineLayoutCreateInfo pipeline_layout_info = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1,
        .pSetLayouts = &p->descriptor_layout,
    };
    if (!vk_success(p, vkCreatePipelineLayout(
                        p->device, &pipeline_layout_info, NULL,
                        &p->pipeline_layout),
                    "creating conversion pipeline layout"))
        return false;

    VkShaderModuleCreateInfo shader_info = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = sizeof(aimagereader_comp_spv),
        .pCode = aimagereader_comp_spv,
    };
    VkShaderModule shader = VK_NULL_HANDLE;
    if (!vk_success(p, vkCreateShaderModule(p->device, &shader_info, NULL,
                                            &shader),
                    "creating conversion shader"))
        return false;

    VkComputePipelineCreateInfo pipeline_info = {
        .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .stage = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .stage = VK_SHADER_STAGE_COMPUTE_BIT,
            .module = shader,
            .pName = "main",
        },
        .layout = p->pipeline_layout,
    };
    VkResult result = vkCreateComputePipelines(
        p->device, VK_NULL_HANDLE, 1, &pipeline_info, NULL, &p->pipeline);
    vkDestroyShaderModule(p->device, shader, NULL);
    if (!vk_success(p, result, "creating AHardwareBuffer conversion pipeline"))
        return false;

    VkDescriptorPoolSize pool_sizes[] = {
        {
            .type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = OUTPUT_COUNT * sampler_descriptors,
        }, {
            .type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .descriptorCount = OUTPUT_COUNT,
        },
    };
    VkDescriptorPoolCreateInfo pool_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .maxSets = OUTPUT_COUNT,
        .poolSizeCount = MP_ARRAY_SIZE(pool_sizes),
        .pPoolSizes = pool_sizes,
    };
    if (!vk_success(p, vkCreateDescriptorPool(p->device, &pool_info, NULL,
                                               &p->descriptor_pool),
                    "creating conversion descriptor pool"))
        return false;

    VkDescriptorSetLayout layouts[OUTPUT_COUNT];
    for (int n = 0; n < OUTPUT_COUNT; n++)
        layouts[n] = p->descriptor_layout;
    VkDescriptorSet descriptors[OUTPUT_COUNT];
    VkDescriptorSetAllocateInfo descriptor_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = p->descriptor_pool,
        .descriptorSetCount = OUTPUT_COUNT,
        .pSetLayouts = layouts,
    };
    if (!vk_success(p, vkAllocateDescriptorSets(
                        p->device, &descriptor_info, descriptors),
                    "allocating conversion descriptors"))
        return false;

    for (int n = 0; n < OUTPUT_COUNT; n++) {
        struct vk_output *output = &p->outputs[n];
        output->descriptor = descriptors[n];
        VkDescriptorImageInfo target = {
            .imageView = output->view,
            .imageLayout = VK_IMAGE_LAYOUT_GENERAL,
        };
        VkWriteDescriptorSet write = {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = output->descriptor,
            .dstBinding = 1,
            .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .pImageInfo = &target,
        };
        vkUpdateDescriptorSets(p->device, 1, &write, 0, NULL);
    }

    mp_info(p->log, "Using Vulkan AHardwareBuffer GPU conversion "
                    "(source format %d, external format 0x%" PRIx64
                    ", output format %d, sampler descriptors %u, "
                    "chroma filter %s)\n",
            p->source_format, p->external_format, p->output_format,
            sampler_descriptors,
            chroma_filter == VK_FILTER_LINEAR ? "linear" : "nearest");
    return true;
}

static struct vk_input *find_input(struct aimagereader_vk *p,
                                   AHardwareBuffer *buffer)
{
    for (int n = 0; n < p->num_inputs; n++) {
        if (p->inputs[n].buffer == buffer)
            return &p->inputs[n];
    }
    return NULL;
}

static struct vk_input *select_input_slot(struct aimagereader_vk *p)
{
    for (int n = 0; n < p->num_inputs; n++) {
        if (!p->inputs[n].buffer)
            return &p->inputs[n];
    }
    if (p->num_inputs < INPUT_CACHE_SIZE)
        return &p->inputs[p->num_inputs];

    struct vk_input *oldest = NULL;
    for (int n = 0; n < p->num_inputs; n++) {
        struct vk_input *input = &p->inputs[n];
        if (!input->users &&
            (!oldest || input->last_used < oldest->last_used))
            oldest = input;
    }
    if (!oldest) {
        mp_err(p->log, "All AHardwareBuffer import slots are in use\n");
        return NULL;
    }

    destroy_input(p, oldest);
    return oldest;
}

static struct vk_input *create_input(struct aimagereader_vk *p,
                                     AHardwareBuffer *buffer,
                                     const AHardwareBuffer_Desc *desc)
{
    if (!desc->width || !desc->height || desc->layers != 1 ||
        !(desc->usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE)) {
        mp_err(p->log, "Unsupported Android hardware-buffer description "
                       "(%ux%u, layers %u, usage 0x%" PRIx64 ")\n",
               desc->width, desc->height, desc->layers, desc->usage);
        return NULL;
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID format_props = {
        .sType =
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID,
    };
    VkAndroidHardwareBufferPropertiesANDROID buffer_props = {
        .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID,
        .pNext = &format_props,
    };
    if (!vk_success(p, p->GetAHBProperties(p->device, buffer, &buffer_props),
                    "querying Android hardware-buffer properties"))
        return NULL;
    if (format_props.format == VK_FORMAT_UNDEFINED &&
        !format_props.externalFormat) {
        mp_err(p->log, "Android hardware buffer has no Vulkan format\n");
        return NULL;
    }

    if (!p->resources_ready) {
        if (!create_outputs(p, desc, &format_props) ||
            !create_pipeline(p, &format_props)) {
            destroy_conversion_resources(p);
            return NULL;
        }
        p->resources_ready = true;
    } else if (p->width != (int)desc->width ||
               p->height != (int)desc->height ||
               !same_source_format(p, desc, &format_props)) {
        mp_err(p->log, "Android hardware-buffer format changed without "
                       "reconfiguring the mapper\n");
        return NULL;
    }

    struct vk_input *input = select_input_slot(p);
    if (!input)
        return NULL;

    VkExternalFormatANDROID external_format = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID,
        .externalFormat = format_props.externalFormat,
    };
    VkExternalMemoryImageCreateInfo external_memory = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
        .pNext = format_props.externalFormat ? &external_format : NULL,
        .handleTypes =
            VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,
    };
    VkImageCreateInfo image_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = &external_memory,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = format_props.externalFormat
            ? VK_FORMAT_UNDEFINED
            : format_props.format,
        .extent = {desc->width, desc->height, 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    if (!vk_success(p, vkCreateImage(p->device, &image_info, NULL,
                                     &input->image),
                    "creating imported Android hardware-buffer image"))
        goto error;

    uint32_t memory_type =
        find_memory_type(p, buffer_props.memoryTypeBits, 0);
    if (memory_type == UINT32_MAX) {
        mp_err(p->log, "No memory type for Android hardware buffer\n");
        goto error;
    }

    VkImportAndroidHardwareBufferInfoANDROID import_info = {
        .sType =
            VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
        .buffer = buffer,
    };
    VkMemoryDedicatedAllocateInfo dedicated_info = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
        .pNext = &import_info,
        .image = input->image,
    };
    VkMemoryAllocateInfo alloc_info = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .pNext = &dedicated_info,
        .allocationSize = buffer_props.allocationSize,
        .memoryTypeIndex = memory_type,
    };
    if (!vk_success(p, vkAllocateMemory(p->device, &alloc_info, NULL,
                                        &input->memory),
                    "importing Android hardware-buffer memory") ||
        !vk_success(p, vkBindImageMemory(p->device, input->image,
                                         input->memory, 0),
                    "binding Android hardware-buffer memory"))
        goto error;

    VkSamplerYcbcrConversionInfo conversion_info = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO,
        .conversion = p->conversion,
    };
    VkImageViewCreateInfo view_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .pNext = p->conversion ? &conversion_info : NULL,
        .image = input->image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = format_props.externalFormat
            ? VK_FORMAT_UNDEFINED
            : format_props.format,
        .components = {
            VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY,
        },
        .subresourceRange = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .levelCount = 1,
            .layerCount = 1,
        },
    };
    if (!vk_success(p, vkCreateImageView(p->device, &view_info, NULL,
                                         &input->view),
                    "creating Android hardware-buffer image view"))
        goto error;

    input->buffer = buffer;
    if (input == &p->inputs[p->num_inputs])
        p->num_inputs++;
    return input;

error:
    destroy_input(p, input);
    return NULL;
}

static void release_output_after_error(struct aimagereader_vk *p,
                                       struct vk_output *output,
                                       bool waited)
{
    if (!output->released)
        return;

    pl_vulkan_release_ex(p->gpu, pl_vulkan_release_params(
        .tex = output->pltex,
        .layout = VK_IMAGE_LAYOUT_GENERAL,
        .qf = VK_QUEUE_FAMILY_IGNORED,
        .semaphore = waited ? (pl_vulkan_sem){.sem = output->available}
                            : (pl_vulkan_sem){0},
    ));
}

static bool reset_acquire_semaphore(struct aimagereader_vk *p,
                                    struct vk_output *output)
{
    if (output->acquire)
        vkDestroySemaphore(p->device, output->acquire, NULL);
    output->acquire = VK_NULL_HANDLE;

    VkSemaphoreCreateInfo semaphore_info = {
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
    };
    return vk_success(p, vkCreateSemaphore(p->device, &semaphore_info, NULL,
                                           &output->acquire),
                      "resetting AImage acquire semaphore");
}

static bool import_acquire_fence(struct aimagereader_vk *p,
                                 struct vk_output *output, int fd)
{
    if (fd < 0)
        return true;
    if (!output->acquire && !reset_acquire_semaphore(p, output))
        return false;

    VkImportSemaphoreFdInfoKHR import_info = {
        .sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR,
        .semaphore = output->acquire,
        .flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT,
        .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
        .fd = fd,
    };
    VkResult result = p->ImportSemaphoreFdKHR
        ? p->ImportSemaphoreFdKHR(p->device, &import_info)
        : VK_ERROR_EXTENSION_NOT_PRESENT;
    return vk_success(p, result, "importing AImage acquire fence");
}

static bool record_conversion(struct aimagereader_vk *p,
                              struct vk_output *output,
                              struct vk_input *input)
{
    VkCommandBufferBeginInfo begin_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    if (!vk_success(p, vkResetCommandBuffer(output->command, 0),
                    "resetting conversion command buffer") ||
        !vk_success(p, vkBeginCommandBuffer(output->command, &begin_info),
                    "beginning conversion command buffer"))
        return false;

    vkCmdResetQueryPool(output->command, output->query_pool, 0, 2);
    vkCmdWriteTimestamp(output->command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        output->query_pool, 0);

    VkImageMemoryBarrier acquire[] = {
        {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = 0,
            .dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
            .oldLayout = input->initialized
                ? VK_IMAGE_LAYOUT_GENERAL
                : VK_IMAGE_LAYOUT_UNDEFINED,
            .newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
            .dstQueueFamilyIndex = p->queue_family,
            .image = input->image,
            .subresourceRange = {
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .levelCount = 1,
                .layerCount = 1,
            },
        }, {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = 0,
            .dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
            .oldLayout = output->written
                ? VK_IMAGE_LAYOUT_GENERAL
                : VK_IMAGE_LAYOUT_UNDEFINED,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = output->image,
            .subresourceRange = {
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .levelCount = 1,
                .layerCount = 1,
            },
        },
    };
    vkCmdPipelineBarrier(output->command,
                         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                         0, NULL, 0, NULL, MP_ARRAY_SIZE(acquire), acquire);

    vkCmdBindPipeline(output->command, VK_PIPELINE_BIND_POINT_COMPUTE,
                      p->pipeline);
    vkCmdBindDescriptorSets(output->command, VK_PIPELINE_BIND_POINT_COMPUTE,
                            p->pipeline_layout, 0, 1, &output->descriptor,
                            0, NULL);
    vkCmdDispatch(output->command, MP_ALIGN_UP(p->width, 16) / 16,
                  MP_ALIGN_UP(p->height, 16) / 16, 1);

    VkImageMemoryBarrier release[] = {
        {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = VK_ACCESS_SHADER_READ_BIT,
            .dstAccessMask = 0,
            .oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = p->queue_family,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
            .image = input->image,
            .subresourceRange = {
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .levelCount = 1,
                .layerCount = 1,
            },
        }, {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
            .dstAccessMask = 0,
            .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = output->image,
            .subresourceRange = {
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .levelCount = 1,
                .layerCount = 1,
            },
        },
    };
    vkCmdPipelineBarrier(output->command,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0,
                         0, NULL, 0, NULL, MP_ARRAY_SIZE(release), release);
    vkCmdWriteTimestamp(output->command, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                        output->query_pool, 1);

    return vk_success(p, vkEndCommandBuffer(output->command),
                      "ending conversion command buffer");
}

static bool submit_conversion(struct aimagereader_vk *p,
                              struct vk_output *output,
                              bool wait_for_output,
                              bool wait_for_acquire)
{
    VkSemaphore wait_semaphores[2];
    VkPipelineStageFlags wait_stages[2];
    uint32_t wait_count = 0;
    if (wait_for_output) {
        wait_semaphores[wait_count] = output->available;
        wait_stages[wait_count++] = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    }
    if (wait_for_acquire) {
        wait_semaphores[wait_count] = output->acquire;
        wait_stages[wait_count++] = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    }

    VkSubmitInfo submit_info = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = wait_count,
        .pWaitSemaphores = wait_count ? wait_semaphores : NULL,
        .pWaitDstStageMask = wait_count ? wait_stages : NULL,
        .commandBufferCount = 1,
        .pCommandBuffers = &output->command,
        .signalSemaphoreCount = 1,
        .pSignalSemaphores = &output->ready,
    };

    p->vk->lock_queue(p->vk, p->queue_family, 0);
    VkResult result = vkQueueSubmit(p->queue, 1, &submit_info, output->fence);
    p->vk->unlock_queue(p->vk, p->queue_family, 0);
    return vk_success(p, result, "submitting AHardwareBuffer conversion");
}

struct aimagereader_vk *aimagereader_vk_create(
    struct ra_hwdec_mapper *mapper, const struct aimagereader_vk_api *api)
{
    struct aimagereader_vk *p = talloc_zero(NULL, struct aimagereader_vk);
    p->log = mapper->log;
    p->mapper = mapper;
    p->api = *api;

    struct mpvk_ctx *ctx = ra_vk_ctx_get(mapper->owner->ra_ctx);
    p->gpu = ra_pl_get(mapper->ra);
    p->vk = p->gpu ? pl_vulkan_get(p->gpu) : NULL;
    if (!ctx || !p->vk || !p->vk->get_proc_addr || !p->vk->features ||
        !p->vk->queue_compute.count ||
        !p->vk->features->features.shaderStorageImageWriteWithoutFormat ||
        !has_ycbcr_conversion(p->vk) ||
        !has_extension(
            p->vk,
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) ||
        !has_extension(p->vk, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME))
        goto error;

    p->device = p->vk->device;
    p->queue_family = p->vk->queue_compute.index;
    p->output_index = -1;
    vkGetDeviceQueue(p->device, p->queue_family, 0, &p->queue);

    p->GetImageFormatProperties2 = get_image_format_properties2(p->vk);
    VkPhysicalDeviceProperties device_properties;
    vkGetPhysicalDeviceProperties(p->vk->phys_device, &device_properties);
    p->timestamp_period = device_properties.limits.timestampPeriod;

    PFN_vkGetDeviceProcAddr get_device_proc =
        (PFN_vkGetDeviceProcAddr)p->vk->get_proc_addr(
            p->vk->instance, "vkGetDeviceProcAddr");
    if (get_device_proc) {
        p->GetAHBProperties =
            (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)
            get_device_proc(
                p->device,
                "vkGetAndroidHardwareBufferPropertiesANDROID");
        if (has_extension(p->vk, "VK_KHR_external_semaphore_fd") &&
            can_import_sync_fd(p->vk)) {
            p->ImportSemaphoreFdKHR =
                (PFN_vkImportSemaphoreFdKHR)get_device_proc(
                    p->device, "vkImportSemaphoreFdKHR");
        }
    }
    if (!p->queue || !p->GetAHBProperties || !p->GetImageFormatProperties2)
        goto error;

    VkCommandPoolCreateInfo pool_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = p->queue_family,
    };
    if (!vk_success(p, vkCreateCommandPool(p->device, &pool_info, NULL,
                                           &p->command_pool),
                    "creating AHardwareBuffer conversion command pool"))
        goto error;

    mapper->dst_params = mapper->src_params;
    mapper->dst_params.imgfmt = IMGFMT_RGB0;
    mapper->dst_params.hw_subfmt = 0;
    mapper->dst_params_ready = false;
    mapper->dst_params_preserve_repr = false;
    mapper->dst_params_map_coordinates = false;
    mapper->dst_num_components = 0;
    memset(mapper->dst_component_mapping, 0,
           sizeof(mapper->dst_component_mapping));
    MP_INFO(mapper, "Using WebHTV low-power Vulkan AHardwareBuffer GPU conversion\n");
    if (p->ImportSemaphoreFdKHR) {
        MP_VERBOSE(mapper, "Using Vulkan sync_fd for AImage acquire fences\n");
    } else {
        MP_VERBOSE(mapper,
                   "Vulkan sync_fd unavailable; using bounded CPU fence waits\n");
    }
    return p;

error:
    aimagereader_vk_destroy(&p);
    return NULL;
}

void aimagereader_vk_destroy(struct aimagereader_vk **state)
{
    struct aimagereader_vk *p = *state;
    if (!p)
        return;

    if (p->gpu)
        pl_gpu_finish(p->gpu);
    destroy_conversion_resources(p);
    if (p->command_pool)
        vkDestroyCommandPool(p->device, p->command_pool, NULL);

    talloc_free(p);
    *state = NULL;
}

bool aimagereader_vk_can_import_acquire_fence(struct aimagereader_vk *p)
{
    return p && p->ImportSemaphoreFdKHR;
}

void aimagereader_vk_buffer_removed(
    struct aimagereader_vk *p, AHardwareBuffer *buffer)
{
    struct vk_input *input = find_input(p, buffer);
    if (!input || input->users)
        return;
    destroy_input(p, input);
}

bool aimagereader_vk_reuse(struct aimagereader_vk *p,
                           struct mp_image *frame)
{
    for (int n = 0; n < OUTPUT_COUNT; n++) {
        struct vk_output *output = &p->outputs[n];
        if (output->source_frame &&
            output->source_frame->planes[3] == frame->planes[3]) {
            p->mapper->tex[0] = output->ratex;
            return true;
        }
    }
    return false;
}

bool aimagereader_vk_retain_last(struct aimagereader_vk *p,
                                 struct mp_image *frame)
{
    return aimagereader_vk_reuse(p, frame);
}

static int map_internal(struct aimagereader_vk *p, AImage *image,
                        AHardwareBuffer *buffer, int acquire_fence_fd,
                        struct mp_image *frame)
{
    struct vk_output *output = NULL;
    struct mp_image *frame_ref = NULL;
    bool wait_for_output = false;
    bool acquire_imported = false;

    for (int n = 0; n < OUTPUT_COUNT; n++)
        finish_output(p, &p->outputs[n], false);

    AHardwareBuffer_Desc desc;
    p->api.AHardwareBuffer_describe(buffer, &desc);
    struct vk_input *input = find_input(p, buffer);
    if (!input)
        input = create_input(p, buffer, &desc);
    if (!input)
        goto error;

    p->output_index = (p->output_index + 1) % OUTPUT_COUNT;
    output = &p->outputs[p->output_index];
    if (!finish_output(p, output, true))
        goto error;

    wait_for_output = output->released;
    if (wait_for_output) {
        if (!pl_vulkan_hold_ex(p->gpu, pl_vulkan_hold_params(
                .tex = output->pltex,
                .layout = VK_IMAGE_LAYOUT_GENERAL,
                .qf = VK_QUEUE_FAMILY_IGNORED,
                .semaphore = (pl_vulkan_sem){.sem = output->available},
            ))) {
            mp_err(p->log, "Failed reclaiming conversion output texture\n");
            goto error;
        }
    }
    mp_image_unrefp(&output->source_frame);

    VkDescriptorImageInfo source = {
        .imageView = input->view,
        .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    };
    VkWriteDescriptorSet write = {
        .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
        .dstSet = output->descriptor,
        .dstBinding = 0,
        .descriptorCount = 1,
        .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
        .pImageInfo = &source,
    };
    vkUpdateDescriptorSets(p->device, 1, &write, 0, NULL);

    frame_ref = mp_image_new_ref(frame);
    if (!frame_ref || !record_conversion(p, output, input))
        goto error;

    acquire_imported = acquire_fence_fd >= 0;
    if (acquire_imported) {
        if (!import_acquire_fence(p, output, acquire_fence_fd))
            goto error;
        acquire_fence_fd = -1;
    }
    if (!submit_conversion(p, output, wait_for_output, acquire_imported)) {
        if (acquire_imported)
            reset_acquire_semaphore(p, output);
        goto error;
    }

    output->source_image = image;
    output->source_frame = frame_ref;
    output->input = input;
    output->pending = true;
    output->timing_pending = true;
    output->written = true;
    output->released = true;
    input->users++;
    input->last_used = ++p->input_serial;
    input->initialized = true;

    pl_vulkan_release_ex(p->gpu, pl_vulkan_release_params(
        .tex = output->pltex,
        .layout = VK_IMAGE_LAYOUT_GENERAL,
        .qf = VK_QUEUE_FAMILY_IGNORED,
        .semaphore = (pl_vulkan_sem){.sem = output->ready},
    ));
    p->mapper->tex[0] = output->ratex;
    return 0;

error:
    if (acquire_fence_fd >= 0)
        close(acquire_fence_fd);
    mp_image_unrefp(&frame_ref);
    if (output)
        release_output_after_error(p, output, wait_for_output);
    return -1;
}

int aimagereader_vk_map(
    struct aimagereader_vk *p, AImage *image,
    AHardwareBuffer *buffer, const AImageCropRect *crop, int32_t data_space,
    struct mp_image *frame, int *acquire_fence)
{
    (void)crop;
    (void)data_space;
    int fence = *acquire_fence;
    *acquire_fence = -1;
    return map_internal(p, image, buffer, fence, frame);
}
