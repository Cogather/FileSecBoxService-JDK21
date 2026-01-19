package com.example.filesecbox.service;

import com.example.filesecbox.model.*;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class SandboxService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SandboxService.class);

    @Value("${app.product.root.win:D:/webIde/product}")
    private String productRootWin;

    @Value("${app.product.root.linux:/webIde/product}")
    private String productRootLinux;

    private Path productRoot;

    @Autowired
    private StorageService storageService;

    @Autowired
    private SkillExecutor skillExecutor;

    @PostConstruct
    public void init() throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String finalPath = os.contains("win") ? productRootWin : productRootLinux;
        
        this.productRoot = Paths.get(finalPath).toAbsolutePath().normalize();
        Files.createDirectories(productRoot);
        log.info("Sandbox Service initialized with product root: {}", productRoot);
    }

    private Path getAgentRoot(String agentId) {
        Path agentRoot = productRoot.resolve(agentId).normalize();
        ensureAgentDirs(agentRoot);
        return agentRoot;
    }

    /**
     * 确保 agent 下的 skills 和 files 目录默认创建好
     */
    private void ensureAgentDirs(Path agentRoot) {
        try {
            if (!Files.exists(agentRoot.resolve("skills"))) {
                Files.createDirectories(agentRoot.resolve("skills"));
            }
            if (!Files.exists(agentRoot.resolve("files"))) {
                Files.createDirectories(agentRoot.resolve("files"));
            }
        } catch (IOException e) {
            log.error("Failed to initialize agent directories for: {}", agentRoot, e);
            throw new RuntimeException("Failed to initialize agent directories", e);
        }
    }

    /**
     * 校验并转换逻辑路径为物理路径
     */
    private Path resolveLogicalPath(String agentId, String logicalPath) {
        if (logicalPath == null) {
            throw new RuntimeException("Security Error: Path cannot be null.");
        }
        
        // 允许 skills, skills/, files, files/
        boolean isValidPrefix = logicalPath.equals("skills") || logicalPath.startsWith("skills/") ||
                               logicalPath.equals("files") || logicalPath.startsWith("files/");
        
        if (!isValidPrefix) {
            throw new RuntimeException("Security Error: Path must start with 'skills/' or 'files/'. Current path: " + logicalPath);
        }
        Path agentRoot = getAgentRoot(agentId);
        Path physicalPath = agentRoot.resolve(logicalPath).normalize();
        storageService.validateScope(physicalPath, agentRoot);
        return physicalPath;
    }

    /**
     * 1.1 上传技能
     */
    public String uploadSkillReport(String agentId, MultipartFile file) throws IOException {
        log.info("Starting skill upload for agent: {}, file: {}", agentId, file.getOriginalFilename());
        Path skillsDir = getAgentRoot(agentId).resolve("skills");
        Set<String> affectedSkills = new HashSet<>();
        Set<String> skillsWithMd = new HashSet<>();

        storageService.writeLockedVoid(agentId, () -> {
            long start = System.currentTimeMillis();
            Files.createDirectories(skillsDir);

            // 1. 第一次扫描：确定技能列表并校验 SKILL.md
            try (ZipInputStream zis = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
                scanAndValidateSkills(zis, affectedSkills, skillsWithMd);
            } catch (Exception e) {
                if (e instanceof RuntimeException && e.getMessage().contains("Validation Error")) throw e;
                log.warn("UTF-8 scan failed, retrying with GBK...");
                affectedSkills.clear();
                skillsWithMd.clear();
                try (ZipInputStream zisGbk = new ZipInputStream(file.getInputStream(), Charset.forName("GBK"))) {
                    scanAndValidateSkills(zisGbk, affectedSkills, skillsWithMd);
                }
            }

            // 2. 最终合法性检查
            if (affectedSkills.isEmpty()) {
                throw new RuntimeException("Validation Error: No valid skill directory found in ZIP.");
            }
            for (String skill : affectedSkills) {
                if (!skillsWithMd.contains(skill)) {
                    throw new RuntimeException("Validation Error: Skill [" + skill + "] is missing required 'SKILL.md' at its root.");
                }
            }

            // 3. 准备覆盖：先删除旧目录
            for (String skillName : affectedSkills) {
                storageService.deleteRecursively(skillsDir.resolve(skillName));
            }

            // 4. 第二次扫描：正式解压
            try (ZipInputStream zis = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
                extractZip(zis, skillsDir);
            } catch (Exception e) {
                log.warn("UTF-8 extract failed, retrying with GBK...");
                try (ZipInputStream zisGbk = new ZipInputStream(file.getInputStream(), Charset.forName("GBK"))) {
                    extractZip(zisGbk, skillsDir);
                }
            }
            log.info("Skill upload completed in {}ms", System.currentTimeMillis() - start);
        });

        StringBuilder sb = new StringBuilder("Upload successful. Details:\n");
        for (String skill : affectedSkills) {
            sb.append(String.format("- Skill [%s] uploaded to [%s], Status: [Success]\n", 
                skill, skillsDir.resolve(skill).toString().replace('\\', '/')));
        }
        return sb.toString().trim();
    }

    /**
     * 1.2 获取技能描述列表 (支持冗余层级压缩)
     */
    public List<SkillMetadata> getSkillList(String agentId) throws IOException {
        log.info("Fetching skill list for agent: {}", agentId);
        Path agentRoot = getAgentRoot(agentId);
        Path skillsDir = agentRoot.resolve("skills");
        if (!Files.exists(skillsDir)) return Collections.emptyList();

        return storageService.writeLocked(agentId, () -> {
            long start = System.currentTimeMillis();
            
            // 1. 冗余层级压缩处理：平铺嵌套结构 A/B/* -> A/*
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry)) {
                        autoFlattenWrapper(entry);
                    }
                }
            }

            // 2. 获取压缩后的合法列表 (根目录下必须有 SKILL.md)
            List<SkillMetadata> metadataList = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry) && Files.exists(entry.resolve("SKILL.md"))) {
                        metadataList.add(parseSkillMd(entry));
                    }
                }
            }
            log.info("Fetched and processed skill list in {}ms", System.currentTimeMillis() - start);
            return metadataList;
        });
    }

    /**
     * 冗余层级自动压缩：如果 A 目录下仅包含一个子目录 B 且无其他文件，则将 B 的内容提升到 A
     */
    private void autoFlattenWrapper(Path topSkillDir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(topSkillDir)) {
            Iterator<Path> it = stream.iterator();
            if (!it.hasNext()) return;
            Path first = it.next();
            if (it.hasNext()) return; // 超过 1 个项（如同时包含子目录 B 和文件 C），不执行平铺

            if (Files.isDirectory(first)) {
                log.info("Detected redundant wrapper directory at {}, flattening...", first);
                try (DirectoryStream<Path> subStream = Files.newDirectoryStream(first)) {
                    for (Path subItem : subStream) {
                        Files.move(subItem, topSkillDir.resolve(subItem.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Files.delete(first);
                // 递归检测，处理 A/A/A/ 极端情况
                autoFlattenWrapper(topSkillDir);
            }
        }
    }

    /**
     * 1.3 删除技能
     */
    public String deleteSkill(String agentId, String skillName) throws IOException {
// ... (原有代码保持不变)
    }

    /**
     * 1.4 下载技能包 (ZIP)
     */
    public void downloadSkill(String agentId, String skillName, java.io.OutputStream os) throws IOException {
        log.info("Downloading skill for agent: {}, skillName: {}", agentId, skillName);
        if (skillName == null || skillName.trim().isEmpty()) {
            throw new RuntimeException("Skill name cannot be empty.");
        }
        Path skillPath = getAgentRoot(agentId).resolve("skills").resolve(skillName).normalize();
        storageService.validateScope(skillPath, getAgentRoot(agentId).resolve("skills"));

        if (!Files.exists(skillPath) || !Files.isDirectory(skillPath)) {
            throw new IOException("Skill not found or invalid: " + skillName);
        }

        storageService.readLocked(agentId, () -> {
            try (ZipOutputStream zos = new ZipOutputStream(os)) {
                zipDirectory(skillPath, skillName, zos);
            }
            return null;
        });
    }

    private void zipDirectory(Path folder, String parentFolder, ZipOutputStream zos) throws IOException {
        try (Stream<Path> stream = Files.walk(folder)) {
            Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isDirectory(path)) continue;
                String zipEntryName = parentFolder + "/" + folder.relativize(path).toString().replace('\\', '/');
                ZipEntry zipEntry = new ZipEntry(zipEntryName);
                zos.putNextEntry(zipEntry);
                Files.copy(path, zos);
                zos.closeEntry();
            }
        }
    }

    /**
     * 2.1 上传单一文件
     */
    public String uploadFile(String agentId, MultipartFile file) throws IOException {
        log.info("Starting file upload for agent: {}, file: {}", agentId, file.getOriginalFilename());
        Path filesDir = getAgentRoot(agentId).resolve("files");
        String fileName = file.getOriginalFilename();
        Path targetPath = filesDir.resolve(fileName).normalize();
        
        storageService.validateScope(targetPath, getAgentRoot(agentId));
        
        storageService.writeLockedVoid(agentId, () -> {
            long start = System.currentTimeMillis();
            Files.createDirectories(filesDir);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("File upload completed in {}ms", System.currentTimeMillis() - start);
        });
        return "File uploaded successfully: files/" + fileName;
    }

    /**
     * 2.2 列出目录清单 (极致性能优化版)
     */
    public List<String> listFiles(String agentId, String logicalPrefix) throws IOException {
        log.info("Listing files for agent: {}, prefix: {}", agentId, logicalPrefix);
        final Path agentRoot = getAgentRoot(agentId);
        final Path physicalRoot = resolveLogicalPath(agentId, logicalPrefix);
        if (!Files.exists(physicalRoot)) return Collections.emptyList();

        return storageService.readLocked(agentId, () -> {
            long start = System.currentTimeMillis();
            // 使用 try-with-resources 确保 Stream 及时关闭，释放文件句柄
            try (Stream<Path> stream = Files.walk(physicalRoot, 5)) { // 限制深度为 5 层，防止恶意超深目录导致 OOM
                List<String> results = stream.parallel() // 并行处理路径转换
                        .filter(Files::isRegularFile)
                        .map(file -> agentRoot.relativize(file).toString().replace('\\', '/'))
                        .collect(Collectors.toList());
                log.info("Listed {} files in {}ms", results.size(), System.currentTimeMillis() - start);
                return results;
            }
        });
    }

    /**
     * 2.3 读取文件内容
     */
    public FileContentResult getContent(String agentId, String logicalPath, Integer offset, Integer limit) throws IOException {
        log.info("Fetching content for agent: {}, path: {}, offset: {}, limit: {}", agentId, logicalPath, offset, limit);
        
        Path physicalPath = resolveLogicalPath(agentId, logicalPath);
        if (!Files.exists(physicalPath)) throw new IOException("Path not found: " + logicalPath);

        return storageService.readLocked(agentId, () -> {
            long start = System.currentTimeMillis();
            List<String> lines;
            try (Stream<String> lineStream = Files.lines(physicalPath, StandardCharsets.UTF_8)) {
                if (offset != null && limit != null) {
                    lines = lineStream.skip(Math.max(0, offset - 1))
                                      .limit(Math.max(0, limit))
                                      .collect(Collectors.toList());
                } else {
                    lines = lineStream.collect(Collectors.toList());
                }
            }
            String content = String.join("\n", lines);
            log.info("Fetched {} lines in {}ms", lines.size(), System.currentTimeMillis() - start);
            return new FileContentResult(content, lines);
        });
    }

    /**
     * 2.4 写入/新建文件
     */
    public String write(String agentId, WriteRequest request) throws IOException {
        validateSkillMdPlacement(request.getFilePath());
        Path physicalPath = resolveLogicalPath(agentId, request.getFilePath());
        storageService.writeLockedVoid(agentId, () -> {
            storageService.writeBytes(physicalPath, request.getContent().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        });
        return "Successfully created or overwritten file: " + request.getFilePath();
    }

    /**
     * 2.5 精确编辑/替换
     */
    public String edit(String agentId, EditRequest request) throws IOException {
        validateSkillMdPlacement(request.getFilePath());
        Path physicalPath = resolveLogicalPath(agentId, request.getFilePath());
        if (!Files.exists(physicalPath)) throw new IOException("Path not found: " + request.getFilePath());

        storageService.writeLockedVoid(agentId, () -> {
            storageService.preciseEdit(physicalPath, request.getOldString(), request.getNewString(), request.getExpectedReplacements());
        });
        return "Successfully edited file: " + request.getFilePath();
    }

    private void validateSkillMdPlacement(String logicalPath) {
        if (logicalPath == null) return;
        String normalized = logicalPath.replace('\\', '/');
        // 只有在操作 skills/ 目录下的 SKILL.md 时才进行校验
        if (normalized.startsWith("skills/") && normalized.endsWith("/SKILL.md")) {
            // 正确格式应该是 skills/{skill_name}/SKILL.md
            // 拆分后格式应该为 ["skills", "{skill_name}", "SKILL.md"]，长度必须为 3
            String[] parts = normalized.split("/");
            if (parts.length != 3) {
                throw new RuntimeException("Security Error: 'SKILL.md' is a system reserved file. You can only create/edit it at the root of a skill (e.g., skills/my_skill/SKILL.md).");
            }
        } else if (normalized.equals("skills/SKILL.md")) {
            // 针对直接在 skills 目录下创建 SKILL.md 的情况
            throw new RuntimeException("Security Error: 'SKILL.md' is a system reserved file. You can only create/edit it at the root of a skill (e.g., skills/my_skill/SKILL.md).");
        }
    }

    /**
     * 2.6 执行指令 (工作目录固定为应用根目录)
     * 通过 Shell 包装以原生支持 >, >>, | 等重定向和管道符
     */
    public ExecutionResult execute(String agentId, CommandRequest request) throws Exception {
        Path agentRoot = getAgentRoot(agentId);
        if (!Files.exists(agentRoot)) Files.createDirectories(agentRoot);

        String commandLine = request.getCommand().trim();
        if (commandLine.isEmpty()) {
            throw new RuntimeException("Command cannot be empty.");
        }

        // 1. 识别可能受影响的技能（用于执行后自动进行冗余压缩）
        Set<String> affectedSkills = new HashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("skills[\\\\/]([^\\\\/\\s\"'>|&]+)").matcher(commandLine);
        while (matcher.find()) {
            affectedSkills.add(matcher.group(1));
        }

        // 2. 执行命令
        ExecutionResult result = skillExecutor.executeInDir(agentRoot, commandLine);

        // 3. 如果操作了 skills 目录，执行完后立即触发冗余压缩处理
        if (!affectedSkills.isEmpty()) {
            storageService.writeLockedVoid(agentId, () -> {
                for (String skillName : affectedSkills) {
                    Path skillPath = agentRoot.resolve("skills").resolve(skillName);
                    if (Files.isDirectory(skillPath)) {
                        autoFlattenWrapper(skillPath);
                    }
                }
            });
        }

        return result;
    }

    /**
     * 2.7 删除指定文件
     */
    public String deleteFile(String agentId, String logicalPath) throws IOException {
        log.info("Deleting file for agent: {}, path: {}", agentId, logicalPath);
        Path physicalPath = resolveLogicalPath(agentId, logicalPath);

        storageService.writeLockedVoid(agentId, () -> {
            if (!Files.exists(physicalPath)) {
                throw new IOException("Path not found: " + logicalPath);
            }
            storageService.deleteRecursively(physicalPath);
            log.info("Successfully deleted path: {}", logicalPath);
        });
        return "Successfully deleted path: " + logicalPath;
    }

    // --- 内部辅助方法 ---

    private void scanAndValidateSkills(ZipInputStream zis, Set<String> skills, Set<String> skillsWithMd) throws IOException {
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName().replace('\\', '/');
            int slash = name.indexOf('/');
            if (slash != -1) {
                String skillName = name.substring(0, slash);
                skills.add(skillName);
                // 严格校验：SKILL.md 必须在技能根目录下 (即 skill_a/SKILL.md)
                if (name.equals(skillName + "/SKILL.md")) {
                    skillsWithMd.add(skillName);
                }
            } else if (entry.isDirectory()) {
                skills.add(name.replace("/", ""));
            }
            zis.closeEntry();
        }
    }

    private void extractZip(ZipInputStream zis, Path targetDir) throws IOException {
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            Path entryPath = targetDir.resolve(entry.getName()).normalize();
            if (entry.isDirectory()) Files.createDirectories(entryPath);
            else {
                Files.createDirectories(entryPath.getParent());
                Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
            }
            zis.closeEntry();
        }
    }

    private SkillMetadata parseSkillMd(Path skillPath) {
        Path mdPath = skillPath.resolve("SKILL.md");
        SkillMetadata meta = new SkillMetadata();
        meta.setName(skillPath.getFileName().toString());
        meta.setDescription("No description available.");
        if (Files.exists(mdPath)) {
            try (BufferedReader reader = Files.newBufferedReader(mdPath, StandardCharsets.UTF_8)) {
                String line;
                boolean inYaml = false;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.equals("---")) { inYaml = !inYaml; continue; }
                    String lower = trimmed.toLowerCase();
                    if (lower.startsWith("name:") || lower.startsWith("name：")) {
                        meta.setName(trimmed.substring(trimmed.indexOf(trimmed.contains(":")?":":"：")+1).trim());
                    } else if (lower.startsWith("description:") || lower.startsWith("description：")) {
                        meta.setDescription(trimmed.substring(trimmed.indexOf(trimmed.contains(":")?":":"：")+1).trim());
                    }
                }
            } catch (IOException ignored) {}
        }
        return meta;
    }
}
