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

    @PostMapping("/skills/{agentId}/upload")
    public ResponseEntity<ApiResponse<?>> uploadSkill(@PathVariable String agentId, @RequestParam("file") MultipartFile file) {
        log.info("API CALL: uploadSkill, agentId: {}, filename: {}", agentId, file.getOriginalFilename());
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.uploadSkillReport(agentId, file))); }
        catch (Exception e) { 
            log.error("API ERROR: uploadSkill", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @GetMapping("/skills/{agentId}/list")
    public ResponseEntity<ApiResponse<?>> getSkillList(@PathVariable String agentId) {
        log.info("API CALL: getSkillList, agentId: {}", agentId);
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.getSkillList(agentId))); }
        catch (Exception e) { 
            log.error("API ERROR: getSkillList", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @DeleteMapping("/skills/{agentId}/delete")
    public ResponseEntity<ApiResponse<?>> deleteSkill(@PathVariable String agentId, @RequestParam("name") String name) {
        log.info("API CALL: deleteSkill, agentId: {}, skillName: {}", agentId, name);
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.deleteSkill(agentId, name))); }
        catch (Exception e) { 
            log.error("API ERROR: deleteSkill", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @GetMapping("/skills/{agentId}/download")
    public void downloadSkill(@PathVariable String agentId, @RequestParam("name") String name, jakarta.servlet.http.HttpServletResponse response) {
        log.info("API CALL: downloadSkill, agentId: {}, skillName: {}", agentId, name);
        try {
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", "attachment; filename=" + name + ".zip");
            sandboxService.downloadSkill(agentId, name, response.getOutputStream());
        } catch (Exception e) {
            log.error("API ERROR: downloadSkill", e);
            try { response.sendError(500, e.getMessage()); } catch (Exception ignored) {}
        }
    }

    @GetMapping("/skills/{agentId}/unlisted")
    public ResponseEntity<ApiResponse<?>> getUnlistedSkillList(@PathVariable String agentId) {
        log.info("API CALL: getUnlistedSkillList, agentId: {}", agentId);
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.getUnlistedSkillList(agentId))); }
        catch (Exception e) { 
            log.error("API ERROR: getUnlistedSkillList", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @PostMapping("/skills/{agentId}/register")
    public ResponseEntity<ApiResponse<?>> registerSkill(@PathVariable String agentId, @RequestParam("name") String name) {
        log.info("API CALL: registerSkill, agentId: {}, skillName: {}", agentId, name);
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.registerSkill(agentId, name))); }
        catch (Exception e) { 
            log.error("API ERROR: registerSkill", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @PostMapping("/skills/{agentId}/download-url")
    public ResponseEntity<ApiResponse<?>> downloadSkillFromUrl(@PathVariable String agentId, @RequestParam("url") String url) {
        log.info("API CALL: downloadSkillFromUrl, agentId: {}, url: {}", agentId, url);
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.downloadSkillFromUrl(agentId, url))); }
        catch (Exception e) { 
            log.error("API ERROR: downloadSkillFromUrl", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @PostMapping("/files/{agentId}/upload")
    public ResponseEntity<ApiResponse<?>> uploadFile(@PathVariable String agentId, @RequestParam("file") MultipartFile file) {
        log.info("API CALL: uploadFile, agentId: {}, filename: {}", agentId, file.getOriginalFilename());
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.uploadFile(agentId, file))); }
        catch (Exception e) { 
            log.error("API ERROR: uploadFile", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @GetMapping("/{agentId}/files")
    public ResponseEntity<ApiResponse<?>> listFiles(@PathVariable String agentId, @RequestParam("path") String path) {
        log.info("API CALL: listFiles, agentId: {}, path: {}", agentId, path);
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.listFiles(agentId, path))); }
        catch (Exception e) { 
            log.error("API ERROR: listFiles", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @GetMapping("/{agentId}/content")
    public ResponseEntity<ApiResponse<?>> getContent(@PathVariable String agentId, @RequestParam("path") String path,
                                                   @RequestParam(required = false) Integer offset, @RequestParam(required = false) Integer limit) {
        log.info("API CALL: getContent, agentId: {}, path: {}, offset: {}, limit: {}", agentId, path, offset, limit);
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.getContent(agentId, path, offset, limit))); }
        catch (Exception e) { 
            log.error("API ERROR: getContent", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @PostMapping("/{agentId}/write")
    public ResponseEntity<ApiResponse<?>> write(@PathVariable String agentId, @RequestBody WriteRequest request) {
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.write(agentId, request))); }
        catch (Exception e) { 
            log.error("API ERROR: write", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @PostMapping("/{agentId}/edit")
    public ResponseEntity<ApiResponse<?>> edit(@PathVariable String agentId, @RequestBody EditRequest request) {
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.edit(agentId, request))); }
        catch (Exception e) { 
            log.error("API ERROR: edit", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @PostMapping("/{agentId}/execute")
    public ResponseEntity<ApiResponse<?>> execute(@PathVariable String agentId, @RequestBody CommandRequest request) {
        try {
            ExecutionResult result = sandboxService.execute(agentId, request);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) { 
            log.error("API ERROR: execute", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }

    @DeleteMapping("/{agentId}/delete")
    public ResponseEntity<ApiResponse<?>> deleteFile(@PathVariable String agentId, @RequestParam("path") String path) {
        log.info("API CALL: deleteFile, agentId: {}, path: {}", agentId, path);
        try { return ResponseEntity.ok(ApiResponse.success(sandboxService.deleteFile(agentId, path))); }
        catch (Exception e) { 
            log.error("API ERROR: deleteFile", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage())); 
        }
    }
}

