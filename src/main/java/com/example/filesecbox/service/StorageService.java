package com.example.filesecbox.service;

import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 通用文件处理类：负责底层的物理 I/O 操作、安全校验以及全局并发锁管理。
 * 锁粒度：agentId。
 */
@Service
public class StorageService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StorageService.class);
    private final Map<String, ReadWriteLock> locks = new ConcurrentHashMap<>();

    private ReadWriteLock getLock(String agentId) {
        return locks.computeIfAbsent(agentId, k -> new ReentrantReadWriteLock());
    }

    public <T> T readLocked(String agentId, IOCallable<T> action) throws IOException {
        ReadWriteLock lock = getLock(agentId);
        try {
            if (lock.readLock().tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) {
                try {
                    return action.call();
                } finally {
                    lock.readLock().unlock();
                }
            } else {
                log.warn("Read lock timeout for agent: {}", agentId);
                throw new IOException("Server busy: Read operation timed out. Please try again.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Operation interrupted.");
        }
    }

    public <T> T writeLocked(String agentId, IOCallable<T> action) throws IOException {
        ReadWriteLock lock = getLock(agentId);
        try {
            if (lock.writeLock().tryLock(10, java.util.concurrent.TimeUnit.SECONDS)) {
                try {
                    return action.call();
                } finally {
                    lock.writeLock().unlock();
                }
            } else {
                log.warn("Write lock timeout for agent: {}", agentId);
                throw new IOException("Server busy: Update operation timed out. Please try again.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Operation interrupted.");
        }
    }

    public void writeLockedVoid(String agentId, IOVoidAction action) throws IOException {
        ReadWriteLock lock = getLock(agentId);
        try {
            if (lock.writeLock().tryLock(10, java.util.concurrent.TimeUnit.SECONDS)) {
                try {
                    action.run();
                } finally {
                    lock.writeLock().unlock();
                }
            } else {
                log.warn("Write lock timeout for agent: {}", agentId);
                throw new IOException("Server busy: Update operation timed out. Please try again.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Operation interrupted.");
        }
    }

    /**
     * 路径安全校验：确保目标路径在应用根目录下
     */
    public void validateScope(Path target, Path agentRoot) {
        Path normalizedTarget = target.normalize().toAbsolutePath();
        Path normalizedRoot = agentRoot.normalize().toAbsolutePath();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            throw new RuntimeException("Security Error: Access out of scope. Path: " + target);
        }
    }

    public byte[] readAllBytes(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    public byte[] readAllBytesFromInputStream(java.io.InputStream is) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    public void writeBytes(Path path, byte[] content, OpenOption... options) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, content, options);
    }

    public void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            FileSystemUtils.deleteRecursively(path);
        }
    }

    /**
     * 精确编辑逻辑
     */
    public void preciseEdit(Path path, String oldStr, String newStr, int expected) throws IOException {
        if (oldStr == null || oldStr.isEmpty()) {
            throw new RuntimeException("Security Error: 'old_string' cannot be empty for replacement operation.");
        }
        
        String content = new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
        
        // 计算匹配次数
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(oldStr, index)) != -1) {
            count++;
            index += oldStr.length();
        }

        if (count != expected) {
            throw new RuntimeException(String.format(
                "Edit Mismatch: '%s' found %d times, but expected %d times. Please refine your search string.",
                oldStr, count, expected
            ));
        }

        String newContent = content.replace(oldStr, newStr);
        Files.write(path, newContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    public interface IOCallable<T> {
        T call() throws IOException;
    }

    @FunctionalInterface
    public interface IOVoidAction {
        void run() throws IOException;
    }
}
