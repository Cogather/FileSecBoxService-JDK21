package com.example.filesecbox.service;

import com.example.filesecbox.model.ExecutionResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class SkillExecutor {

    @Value("${app.product.root.win:D:/webIde/product}")
    private String productRootWin;

    @Value("${app.product.root.linux:/webIde/product}")
    private String productRootLinux;

    // 开放的基础指令白名单
    private static final Set<String> ALLOWED_COMMANDS = new HashSet<>(Arrays.asList(
            "python", "python3", "bash", "sh", "cmd", "ls", "cat", "echo", "grep", "sed",
            "mkdir", "touch", "cp", "mv", "rm", "tee", "find", "chmod", "xargs", "curl"
    ));

    private static final int TIMEOUT_SECONDS = 300; // 5分钟超时

    public ExecutionResult executeInDir(Path workingDir, String commandLine) throws Exception {
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        Charset sysCharset = isWin ? Charset.forName("GBK") : StandardCharsets.UTF_8;

        // 1. 安全校验：禁止路径穿越
        if (commandLine.contains("..")) {
            throw new RuntimeException("Security Error: Path traversal '..' is strictly forbidden.");
        }

        // 2. 指令白名单校验：仅校验首个指令
        String firstCmd = commandLine.trim().split("\\s+")[0];
        if (firstCmd.startsWith("\"") && firstCmd.endsWith("\"")) {
            firstCmd = firstCmd.substring(1, firstCmd.length() - 1);
        }
        if (!ALLOWED_COMMANDS.contains(firstCmd)) {
            throw new RuntimeException("Security Error: Command '" + firstCmd + "' is not allowed.");
        }

        // 3. 强制路径前缀校验
        String argsPart = commandLine.trim().substring(firstCmd.length()).trim();
        if (!argsPart.isEmpty()) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"([^\"]+)\"|([^\\s><|&]+)").matcher(argsPart);
            while (matcher.find()) {
                String potentialPath = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                if (potentialPath.length() < 3 || potentialPath.matches("^-+.*") || potentialPath.matches("^[\\d.]+$")) continue;
                
                if (potentialPath.contains("/") || potentialPath.contains("\\") || potentialPath.contains(".")) {
                    String normalized = potentialPath.replace('\\', '/').toLowerCase();
                    if (!normalized.startsWith("skills/") && !normalized.startsWith("files/") &&
                        !normalized.equals("skills") && !normalized.equals("files")) {
                        throw new RuntimeException("Security Error: Path '" + potentialPath + "' is out of operable scope. Must start with 'skills/' or 'files/'.");
                    }

                    // --- 新增：自动创建目录逻辑 (增强版) ---
                    try {
                        String cleanPath = potentialPath.replace('\\', '/');
                        Path targetPath = workingDir.resolve(cleanPath).normalize();
                        Path dirToCreate = cleanPath.contains(".") ? targetPath.getParent() : targetPath;
                        if (dirToCreate != null && !java.nio.file.Files.exists(dirToCreate)) {
                            java.nio.file.Files.createDirectories(dirToCreate);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // 4. 参数路径校验
        validatePathSecurity(commandLine, workingDir, isWin);

        // 4. 构建进程：通过 Shell 包装以支持 > | >> 等操作
        ProcessBuilder pb = new ProcessBuilder();
        if (isWin) {
            pb.command("cmd", "/c", commandLine);
        } else {
            pb.command("bash", "-c", commandLine);
        }
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(false);

        // 5. 环境净化 (Linux)
        Map<String, String> env = pb.environment();
        if (!isWin) {
            Set<String> safeEnvVars = new HashSet<>(Arrays.asList("PATH", "LANG", "LC_ALL", "HOME", "USER", "PWD"));
            env.keySet().removeIf(key -> !safeEnvVars.contains(key));
            env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        }

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new ExecutionResult("", "Failed to start process: " + e.getMessage(), 127);
        }

        StringBuilder stdoutBuilder = new StringBuilder();
        StringBuilder stderrBuilder = new StringBuilder();
        Thread outThread = new Thread(() -> captureStream(process.getInputStream(), stdoutBuilder, sysCharset));
        Thread errThread = new Thread(() -> captureStream(process.getErrorStream(), stderrBuilder, sysCharset));
        outThread.start();
        errThread.start();

        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Execution Timeout: Process killed after " + TIMEOUT_SECONDS + " seconds.");
        }
        
        outThread.join(1000);
        errThread.join(1000);

        return new ExecutionResult(
            stdoutBuilder.toString().trim(),
            stderrBuilder.toString().trim(),
            process.exitValue()
        );
    }

    private void captureStream(InputStream is, StringBuilder builder, Charset charset) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append("\n");
            }
        } catch (IOException ignored) {}
    }

    private void validatePathSecurity(String arg, Path workingDir, boolean isWin) {
        String productRoot = isWin ? productRootWin : productRootLinux;
        String normRoot = Paths.get(productRoot).toAbsolutePath().normalize().toString().replace("\\", "/").toLowerCase();
        String normArg = arg.replace("\\", "/").toLowerCase();
        
        if (normArg.contains(normRoot)) {
            Path targetPath = Paths.get(arg).toAbsolutePath().normalize();
            if (!targetPath.startsWith(workingDir.toAbsolutePath().normalize())) {
                throw new RuntimeException("Security Error: Accessing path outside workspace scope: " + arg);
            }
        }
    }
}
