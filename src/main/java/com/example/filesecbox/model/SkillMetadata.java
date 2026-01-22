package com.example.filesecbox.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkillMetadata {
    private String name;
    private String description;
    private String status; // UNCHANGED, MODIFIED, NEW, OUT_OF_SYNC
    private String lastSyncTime;
}
