package com.fongmi.android.tv.node;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 锁定猫源端口选择：魔改 bundle（如把弹幕服务器合并进去的）会在同一进程里起多个 HTTP 服务，
 * 句柄顺序不保证猫源那个在前。只落第一个端口会让 App 连到附带服务，取配置吃 401 信封，
 * sites 解析为空——表现为「订阅无效」且无任何报错。
 */
public class NodePortSelectionTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void bootPublishesEveryServerPortWithPreferredFirst() throws IOException {
        String source = read("com/fongmi/android/tv/node/NodeBoot.java");
        int publish = source.indexOf("const publish = () =>");
        assertTrue("NodeBoot 必须生成 publish()", publish >= 0);

        int collect = source.indexOf("ports.push(a.port)", publish);
        assertTrue("必须收集全部 Server 句柄的端口，而不是只取第一个", collect > publish);
        assertTrue("不得在拿到第一个端口后就直接落盘返回",
                source.indexOf("writeFileSync('\" + portEscaped + \"', String(a.port))") < 0);

        int unshift = source.indexOf("ports.unshift(want)", collect);
        assertTrue("我们通过 DEV_HTTP_PORT 指定的端口必须排在候选最前", unshift > collect);

        int write = source.indexOf("ports.join(',')", unshift);
        assertTrue("候选端口必须逗号分隔一起落盘", write > unshift);
    }

    @Test
    public void bootWritesPortFileAtomically() throws IOException {
        String source = read("com/fongmi/android/tv/node/NodeBoot.java");
        int tmp = source.indexOf("portEscaped + \".tmp'");
        int rename = source.indexOf("renameSync", tmp < 0 ? 0 : tmp);
        assertTrue("端口文件要先写临时文件，避免 Java 侧并发读到半截端口号", tmp >= 0);
        assertTrue("临时文件必须 rename 到位", rename > tmp);
    }

    /** 目标端口始终不出现时（bundle 忽略 DEV_HTTP_PORT），轮询会跑满 30 秒——期间不该反复重写同样的内容。 */
    @Test
    public void bootSkipsRewriteWhenCandidatesUnchanged() throws IOException {
        String source = read("com/fongmi/android/tv/node/NodeBoot.java");
        int guard = source.indexOf("if (text === last) return");
        int write = source.indexOf("writeFileSync", guard < 0 ? 0 : guard);
        assertTrue("候选集没变要提前返回，不重写端口文件", guard >= 0);
        assertTrue("提前返回必须排在写文件之前", write > guard);
    }

    @Test
    public void bootKeepsPublishingUntilPreferredPortAppears() throws IOException {
        String source = read("com/fongmi/android/tv/node/NodeBoot.java");
        int ret = source.indexOf("return ports.includes(want)");
        assertTrue("附带服务可能比猫源晚绑定，publish 要到目标端口出现才算完成", ret >= 0);
        assertTrue("轮询仍需有次数上限兜底", source.indexOf("++tries > 150", ret) > ret);
    }

    @Test
    public void waitReadyProbesCandidatesAndValidatesConfigShape() throws IOException {
        String source = read("com/fongmi/android/tv/node/NodeService.java");
        int method = source.indexOf("private int waitReady(");
        assertTrue("NodeService 必须有 waitReady", method >= 0);

        int read = source.indexOf("readPorts(portFile)", method);
        assertTrue("waitReady 必须读候选端口集合", read > method);

        int loop = source.indexOf("for (int candidate : candidates)", read);
        assertTrue("必须逐个探候选端口", loop > read);

        int validate = source.indexOf("CatSource.isConfig(cfg)", loop);
        assertTrue("必须按配置形状认准端口——401 信封和欢迎页都是非空响应，只判空会认错", validate > loop);
        assertTrue("不得再用「响应非空」当就绪判据",
                source.indexOf("if (!TextUtils.isEmpty(text)) return true;") < 0);

        int assign = source.indexOf("return candidate;", validate);
        assertTrue("只有校验通过的端口才可采纳", assign > validate);
    }

    @Test
    public void waitReadyRereadsPortFileEachRound() throws IOException {
        String source = read("com/fongmi/android/tv/node/NodeService.java");
        int method = source.indexOf("private int waitReady(");
        assertTrue("NodeService 必须有 waitReady", method >= 0);
        int read = source.indexOf("readPorts(portFile)", method);
        assertTrue("waitReady 必须在循环内每轮重读端口文件", read > method);
    }

    @Test
    public void readPortsParsesListAndTolueratesLegacySingleValue() throws Exception {
        assertEquals("多端口按逗号解析，顺序保持落盘顺序（猫源在前）",
                Arrays.asList(9988, 9321), readPorts("9988,9321"));
        assertEquals("单端口旧格式要照样能解析", Collections.singletonList(9988), readPorts("9988"));
        assertEquals("带换行/空格也要能解析", Arrays.asList(9988, 9321), readPorts(" 9988 , 9321 \n"));
        assertEquals("坏分片跳过而不是让整体失败", Collections.singletonList(9988), readPorts("9988,abc,0,-1"));
        assertEquals("重复端口去重", Collections.singletonList(9988), readPorts("9988,9988"));
        assertEquals("空内容返回空列表", Collections.emptyList(), readPorts(""));
        assertEquals("纯垃圾内容返回空列表", Collections.emptyList(), readPorts("not-a-port"));
    }

    @Test
    public void readPortsReturnsEmptyWhenFileMissing() throws Exception {
        File missing = new File(folder.getRoot(), "absent");
        assertEquals("端口文件还没写出来时不能抛异常", Collections.emptyList(), invokeReadPorts(missing));
    }

    @SuppressWarnings("unchecked")
    private List<Integer> readPorts(String content) throws Exception {
        File file = folder.newFile();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return invokeReadPorts(file);
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> invokeReadPorts(File file) throws Exception {
        Method method = NodeRuntime.class.getDeclaredMethod("readPorts", File.class);
        method.setAccessible(true);
        return (List<Integer>) method.invoke(null, file);
    }

    private static String read(String relative) throws IOException {
        Path path = mainJava().resolve(Paths.get(relative.replace('/', java.io.File.separatorChar)));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path mainJava() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(Paths.get("app", "src", "main", "java"));
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("app/src/main/java not found from " + Paths.get("").toAbsolutePath());
    }
}
