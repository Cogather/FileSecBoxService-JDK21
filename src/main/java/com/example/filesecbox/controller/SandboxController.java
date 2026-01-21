package com.example.filesecbox.controller;

import com.example.filesecbox.model.*;
import com.example.filesecbox.service.SandboxService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1")
public class SandboxController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SandboxController.class);

    @Autowired
    private SandboxService sandboxService;

    // --- 1. 技能管理 ---

    @PostMapping("/skills/{userId}/{agentId}/upload")
    public ResponseEntity<ApiResponse<?>> uploadSkill(
            @PathVariable String userId,
            @PathVariable String agentId,
            @RequestParam("file") MultipartFile file) {
        log.info("API CALL: uploadSkill, userId: {}, agentId: {}, filename: {}", userId, agentId, file.getOriginalFilename());
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.uploadSkillReport(userId, agentId, file))); }
        catch (Exception e) { 
            log.error("API ERROR: uploadSkill", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @GetMapping("/skills/{userId}/{agentId}/list")
    public ResponseEntity<ApiResponse<?>> getSkillList(
            @PathVariable String userId,
            @PathVariable String agentId) {
        log.info("API CALL: getSkillList, userId: {}, agentId: {}", userId, agentId);
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.getSkillList(userId, agentId, false))); }
        catch (Exception e) { 
            log.error("API ERROR: getSkillList", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @GetMapping("/skills/{userId}/{agentId}/list-with-status")
    public ResponseEntity<ApiResponse<?>> getSkillListWithStatus(
            @PathVariable String userId,
            @PathVariable String agentId) {
        log.info("API CALL: getSkillListWithStatus, userId: {}, agentId: {}", userId, agentId);
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.getSkillList(userId, agentId, true))); }
        catch (Exception e) { 
            log.error("API ERROR: getSkillListWithStatus", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @PostMapping("/skills/{userId}/{agentId}/baseline-sync")
    public ResponseEntity<ApiResponse<?>> baselineSync(
            @PathVariable String userId,
            @PathVariable String agentId,
            @RequestParam("name") String name) {
        log.info("API CALL: baselineSync, userId: {}, agentId: {}, skillName: {}", userId, agentId, name);
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.baselineSync(userId, agentId, name))); }
        catch (Exception e) { 
            log.error("API ERROR: baselineSync", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @DeleteMapping("/skills/{userId}/{agentId}/delete")
    public ResponseEntity<ApiResponse<?>> deleteSkill(
            @PathVariable String userId,
            @PathVariable String agentId,
            @RequestParam("name") String name) {
        log.info("API CALL: deleteSkill, userId: {}, agentId: {}, skillName: {}", userId, agentId, name);
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.deleteSkill(userId, agentId, name))); }
        catch (Exception e) { 
            log.error("API ERROR: deleteSkill", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @GetMapping("/skills/{userId}/{agentId}/download")
    public void downloadSkill(
            @PathVariable String userId,
            @PathVariable String agentId,
            @RequestParam("name") String name,
            jakarta.servlet.http.HttpServletResponse response) {
        log.info("API CALL: downloadSkill, userId: {}, agentId: {}, skillName: {}", userId, agentId, name);
        try {
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", "attachment; filename=" + name + ".zip");
            sandboxService.downloadSkill(userId, agentId, name, response.getOutputStream());
        } catch (Exception e) {
            log.error("API ERROR: downloadSkill", e);
            try { response.sendError(500, e.getMessage()); } catch (Exception ignored) {}
        }
    }

    @PostMapping("/skills/{userId}/{agentId}/install-creator")
    public ResponseEntity<ApiResponse<?>> installCreator(
            @PathVariable String userId,
            @PathVariable String agentId) {
        log.info("API CALL: installCreator, userId: {}, agentId: {}", userId, agentId);
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.installCreator(userId, agentId))); }
        catch (Exception e) { 
            log.error("API ERROR: installCreator", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    // --- 2. 文件与执行管理 ---

    @PostMapping("/files/{userId}/{agentId}/upload")
    public ResponseEntity<ApiResponse<?>> uploadFile(
            @PathVariable String userId,
            @PathVariable String agentId,
            @RequestParam("file") MultipartFile file) {
        log.info("API CALL: uploadFile, userId: {}, agentId: {}, filename: {}", userId, agentId, file.getOriginalFilename());
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.uploadFile(userId, agentId, file))); }
        catch (Exception e) { 
            log.error("API ERROR: uploadFile", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @GetMapping("/{userId}/{agentId}/files")
    public ResponseEntity<ApiResponse<?>> listFiles(
            @PathVariable String userId,
            @PathVariable String agentId,
            @RequestParam("path") String path) {
        log.info("API CALL: listFiles, userId: {}, agentId: {}, path: {}", userId, agentId, path);
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.listFiles(userId, agentId, path))); }
        catch (Exception e) { 
            log.error("API ERROR: listFiles", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @GetMapping("/{userId}/{agentId}/content")
    public ResponseEntity<ApiResponse<?>> getContent(
            @PathVariable String userId,
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit) {
        log.info("API CALL: getContent, userId: {}, agentId: {}, path: {}, offset: {}, limit: {}", userId, agentId, path, offset, limit);
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.getContent(userId, agentId, path, offset, limit))); }
        catch (Exception e) { 
            log.error("API ERROR: getContent", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @PostMapping("/{userId}/{agentId}/write")
    public ResponseEntity<ApiResponse<?>> write(
            @PathVariable String userId,
            @PathVariable String agentId,
            @RequestBody WriteRequest request) {
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.write(userId, agentId, request))); }
        catch (Exception e) { 
            log.error("API ERROR: write", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @PostMapping("/{userId}/{agentId}/edit")
    public ResponseEntity<ApiResponse<?>> edit(
            @PathVariable String userId,
            @PathVariable String agentId,
            @RequestBody EditRequest request) {
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.edit(userId, agentId, request))); }
        catch (Exception e) { 
            log.error("API ERROR: edit", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @PostMapping("/{userId}/{agentId}/execute")
    public ResponseEntity<ApiResponse<?>> execute(
            @PathVariable String userId,
            @PathVariable String agentId,
            @RequestBody CommandRequest request) {
        try {
            ExecutionResult result = sandboxService.execute(userId, agentId, request);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) { 
            log.error("API ERROR: execute", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @DeleteMapping("/{userId}/{agentId}/delete")
    public ResponseEntity<ApiResponse<?>> deleteFile(
            @PathVariable String userId,
            @PathVariable String agentId,
            @RequestParam("path") String path) {
        log.info("API CALL: deleteFile, userId: {}, agentId: {}, path: {}", userId, agentId, path);
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.deleteFile(userId, agentId, path))); }
        catch (Exception e) { 
            log.error("API ERROR: deleteFile", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }
}
