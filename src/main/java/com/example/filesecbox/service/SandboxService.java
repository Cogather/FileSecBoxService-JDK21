package com.example.filesecbox.service;

import com.example.filesecbox.model.*;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class SandboxService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SandboxService.class);

    @Value("${app.product.root.win:D:/webIde/product}")
    private String productRootWin;

    @Value("${app.product.root.linux:/webIde/product}")
    private String productRootLinux;

    @Value("${app.skill.creator.url:}")
    private String skillCreatorUrl;

    private Path productRoot;
    private static final String BASELINE_DIR = "baseline";
    private static final String WORKSPACES_DIR = "workspaces";
    private static final String META_DIR = ".meta";
    private static final String SKILL_CREATOR_DIR = "skill-creator";

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

        // 下载 Skill-Creator
        downloadGlobalSkillCreator();
    }

    /**
     * 下载全局 Skill-Creator
     */
    private void downloadGlobalSkillCreator() {
        if (skillCreatorUrl == null || skillCreatorUrl.trim().isEmpty()) {
            log.warn("Skill Creator URL is not configured. Skipping download.");
            return;
        }
        Path creatorDir = productRoot.resolve(SKILL_CREATOR_DIR);
        if (Files.exists(creatorDir)) {
            log.info("Skill Creator already exists at: {}", creatorDir);
            return;
        }

        try {
            log.info("Downloading global Skill-Creator from: {}", skillCreatorUrl);
            java.net.URL targetUrl = new java.net.URL(skillCreatorUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) targetUrl.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            
            if (conn.getResponseCode() == 200) {
                try (java.io.InputStream is = conn.getInputStream()) {
                    byte[] data = storageService.readAllBytesFromInputStream(is);
                    Files.createDirectories(creatorDir);
                    try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
                        extractZip(zis, creatorDir);
                    }
                }
                log.info("Skill-Creator installed successfully to: {}", creatorDir);
            }
        } catch (Exception e) {
            log.error("Failed to download global Skill-Creator", e);
        }
    }

    private Path getBaselineRoot(String agentId) {
        Path baselineRoot = productRoot.resolve(agentId).resolve(BASELINE_DIR).normalize();
        try {
            Files.createDirectories(baselineRoot.resolve("skills"));
            Files.createDirectories(baselineRoot.resolve("files"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create baseline directories", e);
        }
        return baselineRoot;
    }

    private Path getWorkspaceRoot(String userId, String agentId) {
        Path workspaceRoot = productRoot.resolve(agentId).resolve(WORKSPACES_DIR).resolve(userId).normalize();
        if (!Files.exists(workspaceRoot)) {
            syncWorkspaceFromBaseline(userId, agentId);
        }
        return workspaceRoot;
    }

    /**
     * 从基线同步到工作区 (On-demand Sync)
     */
    private void syncWorkspaceFromBaseline(String userId, String agentId) {
        Path baselineRoot = getBaselineRoot(agentId);
        Path workspaceRoot = productRoot.resolve(agentId).resolve(WORKSPACES_DIR).resolve(userId).normalize();
        
        try {
            log.info("Syncing workspace for user: {} agent: {}", userId, agentId);
            Files.createDirectories(workspaceRoot);
            if (Files.exists(baselineRoot)) {
                FileSystemUtils.copyRecursively(baselineRoot, workspaceRoot);
            }
            
            // 初始化元数据
            Path metaDir = workspaceRoot.resolve(META_DIR);
            Files.createDirectories(metaDir);
            updateWorkspaceMeta(workspaceRoot);
            
        } catch (IOException e) {
            log.error("Failed to sync workspace", e);
            throw new RuntimeException("Failed to initialize user workspace", e);
        }
    }

    private void updateWorkspaceMeta(Path workspaceRoot) throws IOException {
        Path metaFile = workspaceRoot.resolve(META_DIR).resolve("skills_sync.properties");
        Properties props = new Properties();
        Path skillsDir = workspaceRoot.resolve("skills");
        if (Files.exists(skillsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
                for (Path skill : stream) {
                    if (Files.isDirectory(skill)) {
                        props.setProperty(skill.getFileName().toString(), String.valueOf(Files.getLastModifiedTime(skill).toMillis()));
                    }
                }
            }
        }
        try (java.io.OutputStream os = Files.newOutputStream(metaFile)) {
            props.store(os, "Workspace Sync Metadata");
        }
    }

    /**
     * 校验并转换逻辑路径为物理路径
     */
    private Path resolveLogicalPath(String userId, String agentId, String logicalPath) {
        if (logicalPath == null) {
            throw new RuntimeException("Security Error: Path cannot be null.");
        }
        
        // 特殊处理 skill-creator
        if (logicalPath.startsWith("skills/" + SKILL_CREATOR_DIR)) {
            Path creatorRoot = productRoot.resolve(SKILL_CREATOR_DIR);
            String subPath = logicalPath.substring(("skills/" + SKILL_CREATOR_DIR).length());
            if (subPath.startsWith("/")) subPath = subPath.substring(1);
            Path physicalPath = creatorRoot.resolve(subPath).normalize();
            storageService.validateScope(physicalPath, creatorRoot);
            return physicalPath;
        }

        boolean isValidPrefix = logicalPath.equals("skills") || logicalPath.startsWith("skills/") ||
                               logicalPath.equals("files") || logicalPath.startsWith("files/");
        
        if (!isValidPrefix) {
            throw new RuntimeException("Security Error: Path must start with 'skills/' or 'files/'. Current path: " + logicalPath);
        }
        Path workspaceRoot = getWorkspaceRoot(userId, agentId);
        Path physicalPath = workspaceRoot.resolve(logicalPath).normalize();
        storageService.validateScope(physicalPath, workspaceRoot);
        return physicalPath;
    }

    /**
     * 1.1 上传技能 (上传至基线)
     */
    public String uploadSkillReport(String userId, String agentId, MultipartFile file) throws IOException {
        log.info("Starting skill upload to baseline for agent: {}, by user: {}", agentId, userId);
        Path baselineSkillsDir = getBaselineRoot(agentId).resolve("skills");
        
        byte[] data = storageService.readAllBytesFromInputStream(file.getInputStream());
        Set<String> affectedSkills = new HashSet<>();
        Set<String> skillsWithMd = new HashSet<>();

        storageService.writeLockedVoid(agentId, () -> {
            // 校验与解压到基线
            try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
                scanAndValidateSkills(zis, affectedSkills, skillsWithMd);
            }
            
            if (affectedSkills.isEmpty()) throw new RuntimeException("Validation Error: No valid skill directory found.");
            for (String skill : affectedSkills) {
                if (!skillsWithMd.contains(skill)) {
                    throw new RuntimeException("Validation Error: Skill [" + skill + "] missing 'SKILL.md'.");
                }
                storageService.deleteRecursively(baselineSkillsDir.resolve(skill));
            }

            try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
                extractZip(zis, baselineSkillsDir);
            }
        });

        return "Baseline updated successfully. Skills: " + affectedSkills;
    }

    /**
     * 1.2 获取技能列表 (可选带状态比对)
     */
    public List<SkillMetadata> getSkillList(String userId, String agentId, boolean includeStatus) throws IOException {
        Path workspaceRoot = getWorkspaceRoot(userId, agentId);
        Path wsSkillsDir = workspaceRoot.resolve("skills");
        
        if (!Files.exists(wsSkillsDir)) return Collections.emptyList();

        Properties syncMeta = new Properties();
        if (includeStatus) {
            Path metaFile = workspaceRoot.resolve(META_DIR).resolve("skills_sync.properties");
            if (Files.exists(metaFile)) {
                try (java.io.InputStream is = Files.newInputStream(metaFile)) {
                    syncMeta.load(is);
                }
            }
        }

        Path blSkillsDir = getBaselineRoot(agentId).resolve("skills");

        return storageService.readLocked(agentId, () -> {
            List<SkillMetadata> metadataList = new ArrayList<>();
            Set<String> processedSkills = new HashSet<>();

            // 1. 遍历工作区技能
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(wsSkillsDir)) {
                for (Path skillEntry : stream) {
                    if (Files.isDirectory(skillEntry) && Files.exists(skillEntry.resolve("SKILL.md"))) {
                        String skillName = skillEntry.getFileName().toString();
                        if (skillName.equals(SKILL_CREATOR_DIR)) continue; 
                        processedSkills.add(skillName);

                        SkillMetadata meta = parseSkillMd(skillEntry);
                        if (includeStatus) {
                            long currentMtime = Files.getLastModifiedTime(skillEntry).toMillis();
                            long lastSyncMtime = Long.parseLong(syncMeta.getProperty(skillName, "0"));
                            Path blSkillPath = blSkillsDir.resolve(skillName);
                            
                            if (!Files.exists(blSkillPath)) {
                                meta.setStatus("NEW");
                            } else {
                                long blMtime = Files.getLastModifiedTime(blSkillPath).toMillis();
                                if (currentMtime > lastSyncMtime) meta.setStatus("MODIFIED");
                                else if (blMtime > lastSyncMtime) meta.setStatus("OUT_OF_SYNC");
                                else meta.setStatus("UNCHANGED");
                            }
                            meta.setLastSyncTime(formatTime(lastSyncMtime));
                        }
                        metadataList.add(meta);
                    }
                }
            }

            // 2. 如果需要状态，补齐基线中存在但工作区已删除的技能 (DELETED)
            if (includeStatus && Files.exists(blSkillsDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(blSkillsDir)) {
                    for (Path blEntry : stream) {
                        String skillName = blEntry.getFileName().toString();
                        if (Files.isDirectory(blEntry) && !processedSkills.contains(skillName)) {
                            SkillMetadata meta = parseSkillMd(blEntry);
                            meta.setStatus("DELETED");
                            meta.setLastSyncTime(syncMeta.getProperty(skillName, "N/A"));
                            metadataList.add(meta);
                        }
                    }
                }
            }
            return metadataList;
        });
    }

    private String formatTime(long millis) {
        if (millis <= 0) return "Never";
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 技能基线化 (单技能镜像同步)
     */
    public String baselineSync(String userId, String agentId, String skillName) throws IOException {
        Path workspaceRoot = getWorkspaceRoot(userId, agentId);
        Path workspaceSkill = workspaceRoot.resolve("skills").resolve(skillName);
        Path baselineSkillsDir = getBaselineRoot(agentId).resolve("skills");
        Path baselineSkill = baselineSkillsDir.resolve(skillName);

        storageService.writeLockedVoid(agentId, () -> {
            if (Files.exists(workspaceSkill)) {
                // 1. 如果工作区存在：覆盖基线
                storageService.deleteRecursively(baselineSkill);
                Files.createDirectories(baselineSkill.getParent());
                FileSystemUtils.copyRecursively(workspaceSkill, baselineSkill);
                
                log.info("Baseline updated for skill: {}", skillName);
            } else if (Files.exists(baselineSkill)) {
                // 2. 如果工作区不存在且基线存在：删除基线
                storageService.deleteRecursively(baselineSkill);
                log.info("Baseline deleted for skill: {}", skillName);
            } else {
                throw new IOException("Skill not found in both workspace and baseline: " + skillName);
            }
            
            // 更新当前用户的元数据
            updateWorkspaceMeta(workspaceRoot);
        });

        return "Baseline synchronized for skill: " + skillName;
    }

    private void removeFromManifest(Path skillsDir, String skillName) throws IOException {
        Set<String> manifest = getManifest(skillsDir);
        if (manifest.remove(skillName)) {
            saveManifest(skillsDir, manifest);
        }
    }

    public String deleteSkill(String userId, String agentId, String skillName) throws IOException {
        Path wsSkillsDir = getWorkspaceRoot(userId, agentId).resolve("skills");
        Path skillPath = wsSkillsDir.resolve(skillName).normalize();
        storageService.validateScope(skillPath, wsSkillsDir);

        storageService.writeLockedVoid(agentId, () -> {
            if (Files.exists(skillPath)) {
                storageService.deleteRecursively(skillPath);
            }
        });
        return "Successfully deleted skill from workspace: " + skillName;
    }

    public void downloadSkill(String userId, String agentId, String skillName, java.io.OutputStream os) throws IOException {
        Path skillPath = resolveLogicalPath(userId, agentId, "skills/" + skillName);
        if (!Files.exists(skillPath) || !Files.isDirectory(skillPath)) {
            throw new IOException("Skill not found: " + skillName);
        }
        storageService.readLocked(agentId, () -> {
            try (ZipOutputStream zos = new ZipOutputStream(os)) {
                zipDirectory(skillPath, skillName, zos);
            }
            return null;
        });
    }

    public List<SkillMetadata> getUnlistedSkillList(String userId, String agentId) throws IOException {
        Path wsSkillsDir = getWorkspaceRoot(userId, agentId).resolve("skills");
        return storageService.readLocked(agentId, () -> {
            Set<String> manifest = getManifest(wsSkillsDir);
            List<SkillMetadata> metadataList = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(wsSkillsDir)) {
                for (Path entry : stream) {
                    String name = entry.getFileName().toString();
                    if (!manifest.contains(name) && !name.startsWith(".") && Files.isDirectory(entry)) {
                        metadataList.add(parseSkillMd(entry));
                    }
                }
            }
            return metadataList;
        });
    }

    public String registerSkill(String userId, String agentId, String skillName) throws IOException {
        Path wsSkillsDir = getWorkspaceRoot(userId, agentId).resolve("skills");
        Path skillPath = wsSkillsDir.resolve(skillName).normalize();
        storageService.validateScope(skillPath, wsSkillsDir);
        
        storageService.writeLockedVoid(agentId, () -> {
            Set<String> manifest = getManifest(wsSkillsDir);
            if (manifest.add(skillName)) {
                saveManifest(wsSkillsDir, manifest);
            }
        });
        return "Registered skill in workspace: " + skillName;
    }

    public String installCreator(String userId, String agentId) throws IOException {
        // 全局已经在 init 下载，这里确保用户 workspace 知道这个路径即可（实际逻辑在 resolveLogicalPath 拦截）
        return "Global Skill-Creator is ready. You can access it via 'skills/skill-creator'.";
    }

    public String uploadFile(String userId, String agentId, MultipartFile file) throws IOException {
        Path filesDir = getWorkspaceRoot(userId, agentId).resolve("files");
        String fileName = file.getOriginalFilename();
        Path targetPath = filesDir.resolve(fileName).normalize();
        
        storageService.writeLockedVoid(agentId, () -> {
            Files.createDirectories(filesDir);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        });
        return "File uploaded to workspace: files/" + fileName;
    }

    public List<String> listFiles(String userId, String agentId, String logicalPrefix) throws IOException {
        final Path workspaceRoot = getWorkspaceRoot(userId, agentId);
        final Path physicalRoot = resolveLogicalPath(userId, agentId, logicalPrefix);
        if (!Files.exists(physicalRoot)) return Collections.emptyList();

        return storageService.readLocked(agentId, () -> {
            try (Stream<Path> stream = Files.walk(physicalRoot, 5)) {
                return stream.parallel()
                        .filter(Files::isRegularFile)
                        .map(file -> workspaceRoot.relativize(file).toString().replace('\\', '/'))
                        .collect(Collectors.toList());
            }
        });
    }

    public FileContentResult getContent(String userId, String agentId, String logicalPath, Integer offset, Integer limit) throws IOException {
        Path physicalPath = resolveLogicalPath(userId, agentId, logicalPath);
        if (!Files.exists(physicalPath)) throw new IOException("Path not found: " + logicalPath);

        return storageService.readLocked(agentId, () -> {
            List<String> lines;
            try (Stream<String> lineStream = Files.lines(physicalPath, StandardCharsets.UTF_8)) {
                if (offset != null && limit != null) {
                    lines = lineStream.skip(Math.max(0, offset - 1)).limit(Math.max(0, limit)).collect(Collectors.toList());
                } else {
                    lines = lineStream.collect(Collectors.toList());
                }
            }
            return new FileContentResult(String.join("\n", lines), lines);
        });
    }

    public String write(String userId, String agentId, WriteRequest request) throws IOException {
        validateSkillMdPlacement(request.getFilePath());
        Path physicalPath = resolveLogicalPath(userId, agentId, request.getFilePath());
        storageService.writeLockedVoid(agentId, () -> {
            storageService.writeBytes(physicalPath, request.getContent().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        });
        return "Written to workspace: " + request.getFilePath();
    }

    public String edit(String userId, String agentId, EditRequest request) throws IOException {
        validateSkillMdPlacement(request.getFilePath());
        Path physicalPath = resolveLogicalPath(userId, agentId, request.getFilePath());
        storageService.writeLockedVoid(agentId, () -> {
            storageService.preciseEdit(physicalPath, request.getOldString(), request.getNewString(), request.getExpectedReplacements());
        });
        return "Edited in workspace: " + request.getFilePath();
    }

    public ExecutionResult execute(String userId, String agentId, CommandRequest request) throws Exception {
        Path workspaceRoot = getWorkspaceRoot(userId, agentId);
        String command = request.getCommand().trim();
        
        // 执行重定向逻辑：如果命令涉及 skill-creator，将其逻辑路径替换为全局物理路径
        String creatorLogical = "skills/" + SKILL_CREATOR_DIR;
        if (command.contains(creatorLogical)) {
            String creatorPhysical = productRoot.resolve(SKILL_CREATOR_DIR).toAbsolutePath().toString().replace("\\", "/");
            command = command.replace(creatorLogical, creatorPhysical);
            log.info("Command redirected for skill-creator: {}", command);
        }
        
        return skillExecutor.executeInDir(workspaceRoot, command);
    }

    public String deleteFile(String userId, String agentId, String logicalPath) throws IOException {
        Path physicalPath = resolveLogicalPath(userId, agentId, logicalPath);
        storageService.writeLockedVoid(agentId, () -> {
            if (Files.exists(physicalPath)) {
                storageService.deleteRecursively(physicalPath);
            }
        });
        return "Deleted from workspace: " + logicalPath;
    }

    // --- 辅助方法 ---

    private void validateSkillMdPlacement(String logicalPath) {
        if (logicalPath == null) return;
        String normalized = logicalPath.replace('\\', '/');
        if (normalized.startsWith("skills/") && normalized.endsWith("/SKILL.md")) {
            String[] parts = normalized.split("/");
            if (parts.length != 3) {
                throw new RuntimeException("Security Error: 'SKILL.md' can only be at the root of a skill.");
            }
        }
    }

    private void scanAndValidateSkills(ZipInputStream zis, Set<String> skills, Set<String> skillsWithMd) throws IOException {
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName().replace('\\', '/');
            int slash = name.indexOf('/');
            if (slash != -1) {
                String skillName = name.substring(0, slash);
                skills.add(skillName);
                if (name.equals(skillName + "/SKILL.md")) skillsWithMd.add(skillName);
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

    private void zipDirectory(Path folder, String parentFolder, ZipOutputStream zos) throws IOException {
        try (Stream<Path> stream = Files.walk(folder)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(path)) continue;
                String zipEntryName = parentFolder + "/" + folder.relativize(path).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(zipEntryName));
                Files.copy(path, zos);
                zos.closeEntry();
            }
        }
    }

    private SkillMetadata parseSkillMd(Path skillPath) {
        Path mdPath = skillPath.resolve("SKILL.md");
        SkillMetadata meta = new SkillMetadata();
        meta.setName(skillPath.getFileName().toString());
        meta.setDescription("No description.");
        if (Files.exists(mdPath)) {
            try (BufferedReader reader = Files.newBufferedReader(mdPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim().toLowerCase();
                    if (trimmed.startsWith("name:")) meta.setName(line.substring(line.indexOf(":") + 1).trim());
                    if (trimmed.startsWith("description:")) meta.setDescription(line.substring(line.indexOf(":") + 1).trim());
                }
            } catch (IOException ignored) {}
        }
        return meta;
    }

    private void zipDirectory(Path folder, String parentFolder, ZipOutputStream zos) throws IOException {
        try (Stream<Path> stream = Files.walk(folder)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(path)) continue;
                String zipEntryName = parentFolder + "/" + folder.relativize(path).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(zipEntryName));
                Files.copy(path, zos);
                zos.closeEntry();
            }
        }
    }

    private SkillMetadata parseSkillMd(Path skillPath) {
        Path mdPath = skillPath.resolve("SKILL.md");
        SkillMetadata meta = new SkillMetadata();
        meta.setName(skillPath.getFileName().toString());
        meta.setDescription("No description.");
        if (Files.exists(mdPath)) {
            try (BufferedReader reader = Files.newBufferedReader(mdPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim().toLowerCase();
                    if (trimmed.startsWith("name:")) meta.setName(line.substring(line.indexOf(":") + 1).trim());
                    if (trimmed.startsWith("description:")) meta.setDescription(line.substring(line.indexOf(":") + 1).trim());
                }
            } catch (IOException ignored) {}
        }
        return meta;
    }

    private Set<String> getManifest(Path skillsDir) throws IOException {
        Path manifestPath = skillsDir.resolve(".manifest");
        if (!Files.exists(manifestPath)) return new LinkedHashSet<>();
        return new LinkedHashSet<>(Files.readAllLines(manifestPath, StandardCharsets.UTF_8));
    }

    private void saveManifest(Path skillsDir, Set<String> manifest) throws IOException {
        Files.write(skillsDir.resolve(".manifest"), manifest, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void addToManifest(Path skillsDir, Collection<String> skillNames) throws IOException {
        Set<String> manifest = getManifest(skillsDir);
        manifest.addAll(skillNames);
        saveManifest(skillsDir, manifest);
    }

    /**
     * 定时清理任务：每小时执行一次，清理 12 小时未活动的工作区
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void cleanupWorkspaces() {
        log.info("Starting scheduled workspace cleanup...");
        try (DirectoryStream<Path> agentStream = Files.newDirectoryStream(productRoot)) {
            for (Path agentDir : agentStream) {
                Path workspacesDir = agentDir.resolve(WORKSPACES_DIR);
                if (Files.exists(workspacesDir)) {
                    try (DirectoryStream<Path> userStream = Files.newDirectoryStream(workspacesDir)) {
                        for (Path userDir : userStream) {
                            if (Files.isDirectory(userDir)) {
                                long lastAccess = Files.getLastModifiedTime(userDir).toMillis();
                                if (System.currentTimeMillis() - lastAccess > 24 * 3600 * 1000) {
                                    log.info("Cleaning up idle workspace: {}", userDir);
                                    storageService.deleteRecursively(userDir);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("Error during workspace cleanup", e);
        }
    }
}
