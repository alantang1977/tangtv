#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
自动应用自定义修改脚本【完整修复版｜全部私有逻辑脚本动态注入】
读取 custom/config.env 中的配置，自动修改：
  1. app/build.gradle: applicationId（namespace禁止修改）, versionName后缀；自动补全buildFeatures { viewBinding true }
  2. 多语言 strings.xml app_name 系列字段
  3. sync-cnb-release.sh CNB_REPO_SLUG
  4. 工作流 yml：android-release.yml / cnb-release-sync.yml 替换CNB_REPO_SLUG、CNB_REPO_URL
  5. 自动向 android-release.yml 的 Build four release APKs 步骤注入 GRADLE_OPTS 解决R8 OOM内存溢出
  6. AndroidManifest.xml android:label硬编码处理
  7. custom目录自定义图片复制替换

【重要警告】
1. namespace = com.fongmi.android.tv 绝对禁止修改；只修改applicationId(APK安装包ID)
2. main分支保留上游原版android-release.yml，私有补丁全部由本脚本动态注入；上游更新yml不受影响
3. 全部字符串使用标准英文半角减号 "-"，避免Unicode长破折号导致GitHub Action解析失败
用法：python3 custom/apply_custom.py
"""
import os
import re
import shutil
import sys
import xml.etree.ElementTree as ET

# 仓库根目录（脚本在 custom/ 下，上一级就是根目录）
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 自定义图片映射表：custom/ 下的源文件 -> 目标路径列表
IMAGE_MAPPINGS = [
    {
        "source": "startup_logo.png",
        "targets": [
            os.path.join("app", "src", "leanback", "res", "drawable-nodpi", "startup_logo.png"),
            os.path.join("app", "src", "main", "res", "drawable-nodpi", "startup_logo.png"),
        ],
    },
    {
        "source": "mobile_startup.png",
        "targets": [
            os.path.join("app", "src", "mobile", "res", "drawable-nodpi", "mobile_startup.png"),
        ],
    },
]


def load_config(config_path):
    """加载 custom/config.env 配置文件。"""
    config = {}
    if not os.path.exists(config_path):
        print(f"[ERROR] 配置文件不存在: {config_path}")
        sys.exit(1)
    with open(config_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" in line:
                key, value = line.split("=", 1)
                key = key.strip()
                value = value.strip().strip('"').strip("'")
                config[key] = value
    print(f"[INFO] 已加载配置: {list(config.keys())}")
    return config


def modify_build_gradle(config):
    """
    修改 app/build.gradle 中的 applicationId、versionName。
    ⚠️禁止修改 namespace！namespace保持上游原始值 com.fongmi.android.tv
    ✅自动补全 buildFeatures { viewBinding true }，防止databinding编译报错
    """
    package_name = config.get("PACKAGE_NAME", "")
    version_suffix = config.get("VERSION_NAME_SUFFIX", "")
    changed_any = False
    candidate_paths = [
        os.path.join(REPO_ROOT, "app", "build.gradle"),
        os.path.join(REPO_ROOT, "app", "build.gradle.kts"),
    ]
    for path in candidate_paths:
        if not os.path.exists(path):
            continue
        with open(path, "r", encoding="utf-8") as f:
            content = f.read()
        original = content
        filename = os.path.basename(path)

        # ----------只替换 applicationId，完全跳过namespace处理----------
        if package_name:
            # groovy格式 applicationId "xxx"
            content = re.sub(
                r'(applicationId\s+["\'])[^"\']+(["\'])',
                rf"\g<1>{package_name}\g<2>",
                content,
            )
            # kts格式 applicationId = "xxx"
            content = re.sub(
                r'(applicationId\s*=\s*["\'])[^"\']+(["\'])',
                rf"\g<1>{package_name}\g<2>",
                content,
            )

        # ----------versionName 后缀----------
        if version_suffix:
            def add_suffix(m):
                prefix = m.group(1)
                ver = m.group(2)
                quote = m.group(3)
                if not ver.endswith(version_suffix):
                    ver = ver + version_suffix
                return f"{prefix}{ver}{quote}"

            content = re.sub(
                r'(versionName\s*=?\s*["\'])([^"\']+)(["\'])',
                add_suffix,
                content,
            )

        # --------自动保证开启 viewBinding (groovy .gradle 文件)--------
        if filename.endswith(".gradle"):
            if "viewBinding true" not in content:
                if re.search(r'android\s*\{[^}]*buildFeatures', content):
                    # 已有buildFeatures块，插入viewBinding true
                    content = re.sub(
                        r'(buildFeatures\s*\{)',
                        r'\g<1>\n        viewBinding true',
                        content
                    )
                else:
                    # 没有buildFeatures，直接在android{}内插入
                    content = re.sub(
                        r'(android\s*\{)',
                        r'\g<1>\n    buildFeatures {\n        viewBinding true\n    }',
                        content
                    )
                changed_any = True
                print("[OK] app/build.gradle: 已补全 buildFeatures { viewBinding true }")

        if content != original:
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
            changed_any = True
            print(f"[OK] app/{filename}: applicationId -> {package_name}"
                  + (f", versionName加后缀" if version_suffix else ""))
        else:
            print(f"[SKIP] app/{filename}: 未找到可修改的字段（或值已相同）")
    return changed_any


def modify_strings_xml(rel_path, app_name):
    """修改指定 strings.xml 中的 app_name 字段。"""
    full_path = os.path.join(REPO_ROOT, rel_path)
    if not os.path.exists(full_path):
        print(f"[SKIP] 文件不存在: {rel_path}")
        return False
    ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
    ET.register_namespace("tools", "http://schemas.android.com/tools")
    try:
        tree = ET.parse(full_path)
        root = tree.getroot()
    except ET.ParseError as e:
        print(f"[ERROR] 解析 XML 失败 {rel_path}: {e}")
        return False
    changed = False
    for string_elem in root.findall("string"):
        if string_elem.get("name") == "app_name":
            old_value = string_elem.text or ""
            if old_value != app_name:
                string_elem.text = app_name
                changed = True
                print(f"[OK] {rel_path}: app_name \"{old_value}\" -> \"{app_name}\"")
            else:
                print(f"[SKIP] {rel_path}: app_name 已经是 \"{app_name}\"")
    # 同时检查其他名称字段
    for extra_name in ["app_name_tv", "app_name_short", "app_name_long"]:
        for string_elem in root.findall("string"):
            if string_elem.get("name") == extra_name:
                old = string_elem.text or ""
                if old != app_name:
                    string_elem.text = app_name
                    changed = True
                    print(f"[OK] {rel_path}: {extra_name} \"{old}\" -> \"{app_name}\"")
    if changed:
        tree.write(full_path, encoding="utf-8", xml_declaration=True)
    return changed


def modify_cnb_release_script(config):
    """修改 sync-cnb-release.sh 中的 CNB_REPO_SLUG。"""
    cnb_repo_slug = config.get("CNB_REPO_SLUG", "")
    if not cnb_repo_slug:
        print("[SKIP] CNB_REPO_SLUG 未配置")
        return False
    candidate_paths = [
        os.path.join(REPO_ROOT, "github", "scripts", "sync-cnb-release.sh"),
        os.path.join(REPO_ROOT, ".github", "scripts", "sync-cnb-release.sh"),
        os.path.join(REPO_ROOT, "scripts", "sync-cnb-release.sh"),
        os.path.join(REPO_ROOT, "sync-cnb-release.sh"),
    ]
    script_path = None
    for p in candidate_paths:
        if os.path.exists(p):
            script_path = p
            break
    if not script_path:
        print("[SKIP] 未找到 sync-cnb-release.sh")
        return False
    rel_path = os.path.relpath(script_path, REPO_ROOT)
    with open(script_path, "r", encoding="utf-8") as f:
        content = f.read()
    original = content
    content = re.sub(
        r'(CNB_REPO_SLUG\s*=\s*["\'])[^"\']+(["\'])',
        rf"\g<1>{cnb_repo_slug}\g<2>",
        content,
    )
    content = re.sub(
        r'(CNB_REPO_SLUG\s*=\s*"\$\{CNB_REPO_SLUG:-)[^}]+(\}")',
        rf"\g<1>{cnb_repo_slug}\g<2>",
        content,
    )
    if content != original:
        with open(script_path, "w", encoding="utf-8") as f:
            f.write(content)
        print(f"[OK] {rel_path}: CNB_REPO_SLUG -> {cnb_repo_slug}")
        return True
    else:
        print(f"[SKIP] {rel_path}: CNB_REPO_SLUG 已是目标值")
        return False


def modify_android_manifest(config):
    """修改 AndroidManifest.xml 中硬编码的 android:label。"""
    app_name = config.get("APP_NAME", "")
    manifest_path = os.path.join(REPO_ROOT, "app", "src", "main", "AndroidManifest.xml")
    if not os.path.exists(manifest_path):
        return False
    ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
    try:
        tree = ET.parse(manifest_path)
        root = tree.getroot()
    except ET.ParseError:
        return False
    changed = False
    ns = "{http://schemas.android.com/apk/res/android}"
    application = root.find("application")
    if application is not None:
        label = application.get(f"{ns}label")
        if label and not label.startswith("@string/") and not label.startswith("@"):
            old = label
            application.set(f"{ns}label", app_name)
            changed = True
            print(f"[OK] AndroidManifest.xml: android:label \"{old}\" -> \"{app_name}\"")
    if changed:
        tree.write(manifest_path, encoding="utf-8", xml_declaration=True)
    return changed


def replace_custom_images():
    """把 custom/ 目录下的自定义图片复制到目标路径。"""
    custom_dir = os.path.join(REPO_ROOT, "custom")
    changed_any = False
    print("[INFO] 检查 custom/ 目录下的自定义图片...")
    for mapping in IMAGE_MAPPINGS:
        source_file = mapping["source"]
        source_path = os.path.join(custom_dir, source_file)
        if not os.path.exists(source_path):
            print(f"[SKIP] custom/{source_file} 不存在，跳过图片替换")
            continue
        source_size = os.path.getsize(source_path)
        for target_rel in mapping["targets"]:
            target_path = os.path.join(REPO_ROOT, target_rel)
            target_dir = os.path.dirname(target_path)
            os.makedirs(target_dir, exist_ok=True)
            need_copy = True
            if os.path.exists(target_path):
                target_size = os.path.getsize(target_path)
                if target_size == source_size:
                    with open(source_path, "rb") as f1, open(target_path, "rb") as f2:
                        if f1.read() == f2.read():
                            need_copy = False
            if need_copy:
                shutil.copy2(source_path, target_path)
                print(f"[OK] 复制 custom/{source_file} -> {target_rel}")
                changed_any = True
            else:
                print(f"[SKIP] {target_rel} 已是最新，无需替换")
    if not changed_any:
        print("[INFO] 所有图片已是最新，无需替换")
    return changed_any


def modify_workflow_files(config):
    """
    修改工作流yml
    1.替换CNB_REPO_SLUG / CNB_REPO_URL
    2.自动给 Build four release APKs 步骤注入 GRADLE_OPTS，解决R8 OOM
    全部使用标准英文半角减号 "-"，杜绝U+2011非法字符
    """
    cnb_repo_slug = config.get("CNB_REPO_SLUG", "")
    if not cnb_repo_slug:
        print("[SKIP] CNB_REPO_SLUG 未配置，跳过工作流文件修改")
        return False
    cnb_repo_url = f"https://cnb.cool/{cnb_repo_slug}.git"
    workflow_files = [
        os.path.join(".github", "workflows", "android-release.yml"),
        os.path.join(".github", "workflows", "cnb-release-sync.yml"),
    ]
    changed_any = False
    for rel_path in workflow_files:
        full_path = os.path.join(REPO_ROOT, rel_path)
        if not os.path.exists(full_path):
            print(f"[SKIP] {rel_path} 不存在")
            continue
        with open(full_path, "r", encoding="utf-8") as f:
            content = f.read()
        original = content

        # ----替换CNB变量----
        content = re.sub(
            r'(CNB_REPO_SLUG:\s*)[^\s\'"\n]+',
            rf'\g<1>{cnb_repo_slug}',
            content,
        )
        content = re.sub(
            r"(\|\|\s*')[^']+('\s*\}\})",
            rf"\g<1>{cnb_repo_slug}\g<2>",
            content,
        )
        content = re.sub(
            r'(blank\s*=\s*)[^\s\'"\n,]+',
            rf'\g<1>{cnb_repo_slug}',
            content,
        )
        content = re.sub(
            r'(CNB_REPO_URL:\s*)https://[^\s\'"\n]+',
            rf'\g<1>{cnb_repo_url}',
            content,
        )
        content = re.sub(
            r"(\|\|\s*')https://[^']+('\s*\}\})",
            rf"\g<1>{cnb_repo_url}\g<2>",
            content,
        )

        # ----仅针对 android-release.yml：自动注入 GRADLE_OPTS 内存配置----
        if rel_path.endswith("android-release.yml"):
            gradle_env_block = """        env:
          WEBHTV_RELEASE_TAG: ${{ steps.meta.outputs.tag }}
          WEBHTV_APK_SUFFIX: ${{ steps.meta.outputs.apk_suffix }}
          GRADLE_OPTS: "-Xmx4096m -XX:MaxMetaspaceSize=512m"
"""
            pat_has_env = re.compile(
                r'(- name: Build four release APKs\s*\n( +)env:\n(?:\2 +.+\n)*)(\2 +)run:',
                re.MULTILINE
            )
            if pat_has_env.search(content):
                # 已经有env块，追加GRADLE_OPTS
                content = pat_has_env.sub(
                    r'\g<1>\g<3>  GRADLE_OPTS: "-Xmx4096m -XX:MaxMetaspaceSize=512m"\n\g<3>run:',
                    content
                )
                print("[OK] android-release.yml: 追加GRADLE_OPTS到已有env块")
            else:
                # 没有env块，完整插入env
                content = re.sub(
                    r'(- name: Build four release APKs\s*\n)( +)(run:)',
                    rf"\g<1>{gradle_env_block}\g<2>\g<3>",
                    content
                )
                print("[OK] android-release.yml: 注入Build four release APKs env+GRADLE_OPTS")

        if content != original:
            with open(full_path, "w", encoding="utf-8") as f:
                f.write(content)
            changed_any = True
            print(f"[OK] {rel_path}: CNB配置/GRADLE_OPTS已更新")
        else:
            print(f"[SKIP] {rel_path}: 配置已是目标值")
    return changed_any


def main():
    print("=" * 70)
    print("  apply_custom.py【完整修复版｜全部私有逻辑脚本动态注入】")
    print("  注意：main分支保留上游原版android-release.yml，补丁由脚本自动注入")
    print("=" * 70)
    config_path = os.path.join(REPO_ROOT, "custom", "config.env")
    config = load_config(config_path)
    results = []

    print("\n--- [1/7] 修改 app/build.gradle(applicationId + 自动补全viewBinding) ---")
    results.append(modify_build_gradle(config))

    print("\n--- [2/7] 修改 values/strings.xml (app_name) ---")
    app_name = config.get("APP_NAME", "")
    results.append(modify_strings_xml(
        os.path.join("app", "src", "main", "res", "values", "strings.xml"),
        app_name,
    ))

    print("\n--- [3/7] 修改 values-zh-rCN/strings.xml (app_name) ---")
    app_name_zh_cn = config.get("APP_NAME_ZH_CN", app_name)
    results.append(modify_strings_xml(
        os.path.join("app", "src", "main", "res", "values-zh-rCN", "strings.xml"),
        app_name_zh_cn,
    ))

    print("\n--- [4/7] 修改 values-zh-rTW/strings.xml (app_name) ---")
    app_name_zh_tw = config.get("APP_NAME_ZH_TW", app_name)
    results.append(modify_strings_xml(
        os.path.join("app", "src", "main", "res", "values-zh-rTW", "strings.xml"),
        app_name_zh_tw,
    ))

    print("\n--- [5/7] sync-cnb-release.sh / AndroidManifest ---")
    results.append(modify_cnb_release_script(config))
    results.append(modify_android_manifest(config))

    print("\n--- [6/7] 修改工作流：CNB变量 + 自动注入GRADLE_OPTS(R8内存修复) ---")
    results.append(modify_workflow_files(config))

    print("\n--- [7/7] 替换自定义图片 ---")
    results.append(replace_custom_images())

    print("\n" + "=" * 70)
    if any(results):
        print("  [DONE] 全部自定义修改完成，有文件变更")
    else:
        print("  [DONE] 全部文件已是目标状态，无需修改")
    print("=" * 70)


if __name__ == "__main__":
    main()
