package com.example.filesecbox.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SkillMetadata {
    private String name;
    private String description;
    private String status; // UNCHANGED, MODIFIED, NEW, OUT_OF_SYNC
    private String lastSyncTime;
}
