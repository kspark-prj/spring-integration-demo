package com.example.integration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * SFTP에서 다운로드한 CSV 파일 행 데이터를 객체로 파싱하기 위한 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserImportDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String username;
    private String email;
}
