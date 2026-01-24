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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
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

    private void downloadGlobalSkillCreator() {
        if (skillCreatorUrl == null || skillCreatorUrl.trim().isEmpty()) {
            log.warn("Skill Creator URL is not configured. Skipping download.");
            return;
        }
        Path creatorDir = productRoot.resolve(SKILL_CREATOR_DIR);
        
        try {
            log.info("Downloading and refreshing global Skill-Creator from: {}", skillCreatorUrl);
            URL targetUrl = URI.create(skillCreatorUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) targetUrl.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    byte[] data = storageService.readAllBytesFromInputStream(is);
                    
                    // 自动识别 zip 内是否存在唯一的根目录并剥离
                    String commonRoot = detectCommonRoot(data);

                    // 启动时覆盖逻辑：如果目录已存在，先删除
                    if (Files.exists(creatorDir)) {
                        log.info("Cleaning up existing Skill-Creator directory for refresh: {}", creatorDir);
                        storageService.deleteRecursively(creatorDir);
                    }

                    Files.createDirectories(creatorDir);
                    processZipWithFallback(data, zis -> {
                        try {
                            extractZip(zis, creatorDir, commonRoot);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
                log.info("Skill-Creator refreshed successfully to: {}", creatorDir);
            }
        } catch (Exception e) {
            log.error("Failed to download or refresh global Skill-Creator", e);
        }
    }

    private String detectCommonRoot(byte[] data) throws IOException {
        final String[] commonRootHolder = new String[1];
        processZipWithFallback(data, zis -> {
            try {
                commonRootHolder[0] = performDetectCommonRoot(zis);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return commonRootHolder[0];
    }

    private void processZipWithFallback(byte[] data, Consumer<ZipInputStream> action) {
        try {
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
                action.accept(zis);
            }
        } catch (Exception e) {
            // 捕获 IOException, IllegalArgumentException 或 action 抛出的 RuntimeException
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data), Charset.forName("GBK"))) {
                action.accept(zis);
            } catch (Exception ex) {
                throw new RuntimeException("Failed to process ZIP with GBK fallback", ex);
            }
        }
    }

    private String performDetectCommonRoot(ZipInputStream zis) throws IOException {
        String commonRoot = null;
        ZipEntry entry;
        boolean first = true;
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName().replace('\\', '/');
            if (name.isEmpty() || name.equals("/")) continue;
            int slashIdx = name.indexOf('/');
            if (slashIdx == -1) {
                if (!entry.isDirectory()) { commonRoot = null; break; } // 根目录下有文件
                String root = name;
                if (first) { commonRoot = root; first = false; }
                else if (!root.equals(commonRoot)) { commonRoot = null; break; }
            } else {
                String root = name.substring(0, slashIdx);
                if (first) { commonRoot = root; first = false; }
                else if (!root.equals(commonRoot)) { commonRoot = null; break; }
            }
        }
        return commonRoot;
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
        Path skillsDir = workspaceRoot.resolve("skills");
        try {
            if (!Files.exists(workspaceRoot) || !Files.exists(skillsDir) || isDirectoryEmpty(skillsDir)) {
                syncWorkspaceFromBaseline(userId, agentId);
            }
        } catch (IOException e) {
            log.error("Failed to check workspace status, triggering sync anyway", e);
            syncWorkspaceFromBaseline(userId, agentId);
        }
        return workspaceRoot;
    }

    private boolean isDirectoryEmpty(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                return !entries.iterator().hasNext();
            }
        }
        return false;
    }

    private void syncWorkspaceFromBaseline(String userId, String agentId) {
        Path baselineRoot = getBaselineRoot(agentId);
        Path workspaceRoot = productRoot.resolve(agentId).resolve(WORKSPACES_DIR).resolve(userId).normalize();
        
        try {
            log.info("Syncing workspace for user: {} agent: {}", userId, agentId);
            Files.createDirectories(workspaceRoot);
            
            // --- 物理压缩处理 (基线层 A/A -> A) ---
            flattenAllSkills(baselineRoot.resolve("skills"));

            if (Files.exists(baselineRoot)) {
                FileSystemUtils.copyRecursively(baselineRoot.toFile(), workspaceRoot.toFile());
            }
            
            // --- 物理压缩处理 (工作空间层 A/A -> A) ---
            flattenAllSkills(workspaceRoot.resolve("skills"));

            // 兜底：确保工作区下的核心目录一定存在，防止基线拷贝不完整
            Files.createDirectories(workspaceRoot.resolve("skills"));
            Files.createDirectories(workspaceRoot.resolve("files"));
            
            Path metaDir = workspaceRoot.resolve(META_DIR);
            Files.createDirectories(metaDir);
            
            // 首次同步时，对所有从基线拷贝过来的技能更新其同步元数据
            Path wsSkillsDir = workspaceRoot.resolve("skills");
            if (Files.exists(wsSkillsDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(wsSkillsDir)) {
                    for (Path skill : stream) {
                        if (Files.isDirectory(skill)) {
                            String skillName = skill.getFileName().toString();
                            if (!skillName.equals(SKILL_CREATOR_DIR)) {
                                updateWorkspaceMetaForSkill(workspaceRoot, agentId, skillName);
                            }
                        }
                    }
                }
            }
            
        } catch (IOException e) {
            log.error("Failed to sync workspace", e);
            throw new RuntimeException("Failed to initialize user workspace", e);
        }
    }

    private void flattenAllSkills(Path skillsDir) {
        if (!Files.exists(skillsDir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
            for (Path skillEntry : stream) {
                if (Files.isDirectory(skillEntry)) {
                    physicallyFlattenSkill(skillEntry, skillEntry.getFileName().toString());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan skills for flattening: {}", skillsDir, e);
        }
    }

    private void physicallyFlattenSkill(Path skillDir, String skillName) {
        Path nested = isRedundantDirectory(skillDir, skillName);
        if (nested != null) {
            try {
                log.info("Physically flattening redundant directory: {}/{}", skillName, skillName);
                Path tempDir = skillDir.getParent().resolve(skillName + "_tmp_" + System.currentTimeMillis());
                Files.move(nested, tempDir);
                storageService.deleteRecursively(skillDir.toFile().toPath());
                Files.move(tempDir, skillDir);
            } catch (IOException e) {
                log.error("Failed to physically flatten directory: {}", skillDir, e);
            }
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

    private Path resolveLogicalPath(String userId, String agentId, String logicalPath) {
        if (logicalPath == null) {
            throw new RuntimeException("Security Error: Path cannot be null.");
        }
        
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

    private Path isRedundantDirectory(Path dir, String expectedName) {
        try {
            if (!Files.exists(dir) || !Files.isDirectory(dir)) return null;
            // 判定标准：一级目录没有 SKILL.md，且仅包含唯一的同名子目录 (符合 "A下面没有任何内容")
            if (Files.exists(dir.resolve("SKILL.md"))) return null;
            Path nested = dir.resolve(expectedName);
            if (!Files.exists(nested) || !Files.isDirectory(nested)) return null;
            
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                Iterator<Path> it = stream.iterator();
                if (it.hasNext()) {
                    it.next(); // 跳过第一个 (即 nested)
                    if (it.hasNext()) return null; // 还有其他东西，不视为冗余
                }
            }
            return nested;
        } catch (Exception ignored) {}
        return null;
    }

    public String uploadSkillReport(String userId, String agentId, MultipartFile file) throws IOException {
        log.info("Starting skill upload to baseline for agent: {}, by user: {}", agentId, userId);
        Path baselineSkillsDir = getBaselineRoot(agentId).resolve("skills");
        
        byte[] data = storageService.readAllBytesFromInputStream(file.getInputStream());
        Set<String> affectedSkills = new HashSet<>();
        Set<String> skillsWithMd = new HashSet<>();

        storageService.writeLockedVoid(agentId, () -> {
            processZipWithFallback(data, zis -> {
                try {
                    affectedSkills.clear();
                    skillsWithMd.clear();
                    scanAndValidateSkills(zis, affectedSkills, skillsWithMd);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            
            if (affectedSkills.isEmpty()) throw new RuntimeException("Validation Error: No valid skill directory found.");
            
            // --- 新增：禁止上传名为 skill-creator 的技能 ---
            if (affectedSkills.contains(SKILL_CREATOR_DIR)) {
                throw new RuntimeException("Validation Error: Skill name '" + SKILL_CREATOR_DIR + "' is reserved for system tools and cannot be uploaded.");
            }
            
            for (String skill : affectedSkills) {
                storageService.deleteRecursively(baselineSkillsDir.resolve(skill));
            }

            processZipWithFallback(data, zis -> {
                try {
                    extractZip(zis, baselineSkillsDir, null);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            
            // --- 物理压缩处理 (A/A -> A) ---
            flattenAllSkills(baselineSkillsDir);
        });

        return "Baseline updated successfully. Skills: " + affectedSkills;
    }

    public List<SkillMetadata> getSkillList(String userId, String agentId, boolean includeStatus, String role) throws IOException {
        if (includeStatus && "manager".equalsIgnoreCase(role)) {
            storageService.writeLockedVoid(agentId, () -> {
                syncFromBaselineToWorkspace(userId, agentId);
            });
        }

        Path workspaceRoot = getWorkspaceRoot(userId, agentId);
        Path wsSkillsDir = workspaceRoot.resolve("skills");
        
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
        
        // --- 物理压缩处理 (基线层 A/A -> A) ---
        flattenAllSkills(blSkillsDir);

        return storageService.readLocked(agentId, () -> {
            List<SkillMetadata> metadataList = new ArrayList<>();
            Set<String> processedSkills = new HashSet<>();

            if (Files.exists(wsSkillsDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(wsSkillsDir)) {
                    for (Path skillEntry : stream) {
                        if (Files.isDirectory(skillEntry) && Files.exists(skillEntry.resolve("SKILL.md"))) {
                            String skillName = skillEntry.getFileName().toString();
                            processedSkills.add(skillName);

                            SkillMetadata meta = parseSkillMd(skillEntry);
                            if (includeStatus) {
                                String key = Base64.getEncoder().encodeToString(skillName.getBytes(StandardCharsets.UTF_8));
                                long currentMtime = Files.getLastModifiedTime(skillEntry).toMillis();
                                long lastSyncMtime = Long.parseLong(syncMeta.getProperty(key, "0"));
                                Path blSkillPath = blSkillsDir.resolve(skillName);
                                
                                if (!Files.exists(blSkillPath)) {
                                    meta.setStatus("LOCAL_ONLY");
                                } else {
                                    long blMtime = Files.getLastModifiedTime(blSkillPath).toMillis();
                                    if (blMtime > currentMtime) meta.setStatus("OUT_OF_SYNC");
                                    else if (currentMtime > lastSyncMtime) meta.setStatus("MODIFIED");
                                    else meta.setStatus("UNCHANGED");
                                }
                                meta.setLastSyncTime(formatTime(lastSyncMtime));
                            } else {
                                meta.setStatus(null);
                                meta.setLastSyncTime(null);
                            }
                            metadataList.add(meta);
                        }
                    }
                }
            }

            // 补充：默认返回全局的 skill-creator
            Path globalCreatorPath = productRoot.resolve(SKILL_CREATOR_DIR);
            if (Files.exists(globalCreatorPath) && Files.isDirectory(globalCreatorPath)) {
                String creatorName = globalCreatorPath.getFileName().toString();
                if (metadataList.stream().noneMatch(m -> m.getName().equals(creatorName))) {
                    SkillMetadata creatorMeta = parseSkillMd(globalCreatorPath);
                    if (includeStatus) {
                        creatorMeta.setStatus("UNCHANGED");
                        creatorMeta.setLastSyncTime("System");
                    }
                    metadataList.add(creatorMeta);
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

    public String baselineSync(String userId, String agentId, String skillName, String direction) throws IOException {
        Path workspaceRoot = getWorkspaceRoot(userId, agentId);
        Path workspaceSkill = workspaceRoot.resolve("skills").resolve(skillName);
        Path baselineSkillsDir = getBaselineRoot(agentId).resolve("skills");
        Path baselineSkill = baselineSkillsDir.resolve(skillName);

        storageService.writeLockedVoid(agentId, () -> {
            if ("bl2ws".equalsIgnoreCase(direction)) {
                // 基线 -> 工作区 (手动同步)
                if (Files.exists(baselineSkill)) {
                    storageService.deleteRecursively(workspaceSkill);
                    Files.createDirectories(workspaceSkill.getParent());
                    FileSystemUtils.copyRecursively(baselineSkill.toFile(), workspaceSkill.toFile());
                    log.info("Workspace updated from baseline for skill: {}", skillName);
                } else if (Files.exists(workspaceSkill)) {
                    // 如果基线不存在但工作区存在 (LOCAL_ONLY)，同步基线到工作区意味着删除工作区内容
                    storageService.deleteRecursively(workspaceSkill);
                    log.info("Workspace skill deleted during bl2ws sync (not found in baseline): {}", skillName);
                } else {
                    throw new IOException("Skill not found in both baseline and workspace: " + skillName);
                }
            } else {
                // 工作区 -> 基线 (ws2bl, 默认)
                if (Files.exists(workspaceSkill)) {
                    storageService.deleteRecursively(baselineSkill);
                    Files.createDirectories(baselineSkill.getParent());
                    FileSystemUtils.copyRecursively(workspaceSkill.toFile(), baselineSkill.toFile());
                    
                    // --- 物理压缩处理 (A/A -> A) ---
                    physicallyFlattenSkill(baselineSkill, skillName);
                    
                    log.info("Baseline updated for skill: {}", skillName);
                } else if (Files.exists(baselineSkill)) {
                    storageService.deleteRecursively(baselineSkill);
                    log.info("Baseline deleted for skill (workspace not found): {}", skillName);
                } else {
                    throw new IOException("Skill not found in both workspace and baseline: " + skillName);
                }
            }
            updateWorkspaceMetaForSkill(workspaceRoot, agentId, skillName);
        });

        return "Skill synchronization completed (" + (direction != null ? direction : "ws2bl") + ") for: " + skillName;
    }

    private void updateWorkspaceMetaForSkill(Path workspaceRoot, String agentId, String skillName) throws IOException {
        log.info("Updating workspace meta for skill: {} in workspace: {}, agentId: {}", skillName, workspaceRoot, agentId);
        Path metaFile = workspaceRoot.resolve(META_DIR).resolve("skills_sync.properties");
        Properties props = new Properties();
        if (Files.exists(metaFile)) {
            try (java.io.InputStream is = Files.newInputStream(metaFile)) {
                props.load(is);
            }
        }
        Path blSkillPath = getBaselineRoot(agentId).resolve("skills").resolve(skillName);
        Path wsSkillPath = workspaceRoot.resolve("skills").resolve(skillName);

        log.info("Checking baseline path: {}", blSkillPath);
        if (Files.exists(blSkillPath) && Files.isDirectory(blSkillPath)) {
            java.nio.file.attribute.FileTime blTime = Files.getLastModifiedTime(blSkillPath);
            log.info("Baseline exists. Mtime: {}. Updating workspace skill path: {}", blTime.toMillis(), wsSkillPath);
            if (Files.exists(wsSkillPath)) {
                Files.setLastModifiedTime(wsSkillPath, blTime);
            }
            String key = Base64.getEncoder().encodeToString(skillName.getBytes(StandardCharsets.UTF_8));
            props.setProperty(key, String.valueOf(blTime.toMillis()));
        } else {
            log.warn("Baseline skill path does not exist or is not a directory: {}", blSkillPath);
            String key = Base64.getEncoder().encodeToString(skillName.getBytes(StandardCharsets.UTF_8));
            props.remove(key);
        }
        try (java.io.OutputStream os = Files.newOutputStream(metaFile)) {
            props.store(os, "Workspace Sync Metadata Updated (Aligned with Base64 Keys)");
        }
    }

    public String deleteSkill(String userId, String agentId, String skillName) throws IOException {
        Path blSkillsDir = getBaselineRoot(agentId).resolve("skills");
        Path skillPath = blSkillsDir.resolve(skillName).normalize();
        storageService.validateScope(skillPath, blSkillsDir);

        storageService.writeLockedVoid(agentId, () -> {
            if (Files.exists(skillPath)) {
                storageService.deleteRecursively(skillPath);
                log.info("Deleted skill from baseline: {}", skillName);
            }
        });
        return "Successfully deleted skill from baseline: " + skillName;
    }

    private void syncFromBaselineToWorkspace(String userId, String agentId) throws IOException {
        Path baselineSkillsDir = getBaselineRoot(agentId).resolve("skills");
        Path workspaceRoot = getWorkspaceRoot(userId, agentId);
        Path workspaceSkillsDir = workspaceRoot.resolve("skills");

        // 读取上次同步时间
        Properties syncMeta = new Properties();
        Path metaFile = workspaceRoot.resolve(META_DIR).resolve("skills_sync.properties");
        if (Files.exists(metaFile)) {
            try (java.io.InputStream is = Files.newInputStream(metaFile)) {
                syncMeta.load(is);
            }
        }

        // 1. 新增与更新同步
        if (Files.exists(baselineSkillsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(baselineSkillsDir)) {
                for (Path blSkill : stream) {
                    if (Files.isDirectory(blSkill)) {
                        String skillName = blSkill.getFileName().toString();
                        if (skillName.equals(SKILL_CREATOR_DIR)) continue;

                        Path wsSkill = workspaceSkillsDir.resolve(skillName);
                        long blMtime = Files.getLastModifiedTime(blSkill).toMillis();

                        if (!Files.exists(wsSkill)) {
                            // 新增同步
                            log.info("Manager Sync: Adding new skill to workspace: {}", skillName);
                            FileSystemUtils.copyRecursively(blSkill.toFile(), wsSkill.toFile());
                            updateWorkspaceMetaForSkill(workspaceRoot, agentId, skillName);
                        } else {
                            // 更新同步：基线晚于工作区修改时间才同步
                            long wsMtime = Files.getLastModifiedTime(wsSkill).toMillis();
                            if (blMtime > wsMtime) {
                                log.info("Manager Sync: Updating skill in workspace (baseline is newer): {}", skillName);
                                storageService.deleteRecursively(wsSkill);
                                FileSystemUtils.copyRecursively(blSkill.toFile(), wsSkill.toFile());
                                updateWorkspaceMetaForSkill(workspaceRoot, agentId, skillName);
                            }
                        }
                    }
                }
            }
        }

        // 2. 删除同步：工作区存在但基线不存在的技能（且不是系统内置），自动删除
        if (Files.exists(workspaceSkillsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(workspaceSkillsDir)) {
                for (Path wsSkill : stream) {
                    if (Files.isDirectory(wsSkill)) {
                        String skillName = wsSkill.getFileName().toString();
                        if (skillName.equals(SKILL_CREATOR_DIR)) continue;

                        Path blSkill = baselineSkillsDir.resolve(skillName);
                        if (!Files.exists(blSkill)) {
                            log.info("Manager Sync: Deleting skill from workspace (removed from baseline): {}", skillName);
                            storageService.deleteRecursively(wsSkill);
                            updateWorkspaceMetaForSkill(workspaceRoot, agentId, skillName);
                        }
                    }
                }
            }
        }
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

    public String installCreator(String userId, String agentId) throws IOException {
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
        if (request.getFilePath().startsWith("skills/" + SKILL_CREATOR_DIR)) {
            throw new RuntimeException("Security Error: Writing to skill-creator is strictly forbidden.");
        }
        validateSkillMdPlacement(request.getFilePath());
        Path physicalPath = resolveLogicalPath(userId, agentId, request.getFilePath());
        storageService.writeLockedVoid(agentId, () -> {
            storageService.writeBytes(physicalPath, request.getContent().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            if (request.getFilePath().startsWith("skills/")) {
                touchSkillDirectory(userId, agentId, request.getFilePath());
            }
        });
        return "Written to workspace: " + request.getFilePath();
    }

    public String edit(String userId, String agentId, EditRequest request) throws IOException {
        if (request.getFilePath().startsWith("skills/" + SKILL_CREATOR_DIR)) {
            throw new RuntimeException("Security Error: Editing skill-creator is strictly forbidden.");
        }
        validateSkillMdPlacement(request.getFilePath());
        Path physicalPath = resolveLogicalPath(userId, agentId, request.getFilePath());
        if (!Files.exists(physicalPath)) {
            throw new IOException("Edit Error: File not found: " + request.getFilePath());
        }
        storageService.writeLockedVoid(agentId, () -> {
            storageService.preciseEdit(physicalPath, request.getOldString(), request.getNewString(), request.getExpectedReplacements());
            if (request.getFilePath().startsWith("skills/")) {
                touchSkillDirectory(userId, agentId, request.getFilePath());
            }
        });
        return "Edited in workspace: " + request.getFilePath();
    }

    private void touchSkillDirectory(String userId, String agentId, String logicalPath) {
        try {
            String[] parts = logicalPath.split("/");
            if (parts.length >= 2 && parts[0].equals("skills")) {
                String skillName = parts[1];
                Path skillDir = getWorkspaceRoot(userId, agentId).resolve("skills").resolve(skillName);
                if (Files.exists(skillDir) && Files.isDirectory(skillDir)) {
                    Files.setLastModifiedTime(skillDir, java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
                    log.info("Touched skill directory to update mtime: {}", skillDir);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to touch skill directory: {}", logicalPath, e);
        }
    }

    public ExecutionResult execute(String userId, String agentId, CommandRequest request) throws Exception {
        Path workspaceRoot = getWorkspaceRoot(userId, agentId);
        String command = request.getCommand().trim();
        String creatorLogical = "skills/" + SKILL_CREATOR_DIR;
        if (command.contains(creatorLogical)) {
            String creatorPhysical = productRoot.resolve(SKILL_CREATOR_DIR).toAbsolutePath().toString().replace("\\", "/");
            command = command.replace(creatorLogical, creatorPhysical);
            log.info("Command redirected for skill-creator: {}", command);
        }
        ExecutionResult result = skillExecutor.executeInDir(workspaceRoot, command);
        
        // --- 物理压缩处理 (A/A -> A) ---
        flattenAllSkills(workspaceRoot.resolve("skills"));
        
        return result;
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
            // 忽略 macOS 自动生成的冗余目录
            if (name.startsWith("__MACOSX/") || name.contains("/.__")) {
                zis.closeEntry();
                continue;
            }
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

    private void extractZip(ZipInputStream zis, Path targetDir, String rootToSkip) throws IOException {
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName().replace('\\', '/');
            // 忽略 macOS 自动生成的冗余目录
            if (name.startsWith("__MACOSX/") || name.contains("/.__")) {
                zis.closeEntry();
                continue;
            }
            if (rootToSkip != null) {
                if (name.equals(rootToSkip + "/")) continue;
                if (name.startsWith(rootToSkip + "/")) {
                    name = name.substring(rootToSkip.length() + 1);
                }
            }
            if (name.isEmpty()) continue;

            Path entryPath = targetDir.resolve(name).normalize();
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
            Iterator<Path> it = stream.iterator();
            while (it.hasNext()) {
                Path path = it.next();
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
