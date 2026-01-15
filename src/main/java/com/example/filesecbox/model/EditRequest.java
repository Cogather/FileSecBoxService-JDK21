package com.example.filesecbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class EditRequest {
    @JsonProperty("file_path")
    private String filePath;
    @JsonProperty("old_string")
    private String oldString;
    @JsonProperty("new_string")
    private String newString;
    @JsonProperty("expected_replacements")
    private int expectedReplacements;
}
