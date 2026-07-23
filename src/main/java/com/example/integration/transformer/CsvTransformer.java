package com.example.integration.transformer;

import com.example.integration.dto.UserImportDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DB 데이터 ➔ CSV 파일 변환 및 CSV 라인 ➔ DTO 변환을 담당하는 Transformer
 */
@Component
public class CsvTransformer {

    private static final Logger log = LoggerFactory.getLogger(CsvTransformer.class);

    @Value("${demo.db.export-temp-dir}")
    private String exportTempDir;

    /**
     * [요구사항 5] DB 조회 데이터 (List<Map>)를 CSV 포맷 파일로 변환합니다.
     * @param rows DB에서 조회된 레코드 리스트
     * @return 로컬 임시 폴더에 저장된 File 객체
     */
    public File convertToCsvFile(List<Map<String, Object>> rows) {
        log.info("[CsvTransformer] DB 조회 데이터 CSV 변환 시작 (총 {}건)", rows.size());

        // 임시 저장 디렉터리 생성
        File dir = new File(exportTempDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 고유한 파일명 생성
        File csvFile = new File(dir, "user_export_" + UUID.randomUUID() + ".csv");

        try (FileWriter writer = new FileWriter(csvFile)) {
            // 1. 헤더 작성
            writer.write("id,username,email\n");

            // 2. 바디 데이터 작성
            for (Map<String, Object> row : rows) {
                String id = String.valueOf(row.get("id"));
                String username = String.valueOf(row.get("username"));
                String email = String.valueOf(row.get("email"));
                
                // CSV 포맷팅 (단순 콤마 구분)
                writer.write(String.format("%s,%s,%s\n", id, username, email));
            }
            writer.flush();
            log.info("[CsvTransformer] CSV 파일 생성 완료: {}", csvFile.getAbsolutePath());
            return csvFile;

        } catch (IOException e) {
            log.error("[CsvTransformer] CSV 파일 생성 중 에러 발생", e);
            throw new RuntimeException("Failed to generate CSV export file", e);
        }
    }

    /**
     * [요구사항 6] CSV 파일의 한 줄을 파싱하여 UserImportDto로 변환합니다.
     * @param csvLine CSV 파일에서 읽어들인 한 라인
     * @return 파싱된 UserImportDto (헤더 라인이거나 빈 라인인 경우 null 반환)
     */
    public UserImportDto parseCsvLineToDto(String csvLine) {
        if (csvLine == null || csvLine.trim().isEmpty()) {
            return null;
        }

        // CSV 헤더 라인 스킵 처리
        if (csvLine.startsWith("id,username,email")) {
            log.debug("[CsvTransformer] 헤더 라인 스킵: {}", csvLine);
            return null;
        }

        String[] tokens = csvLine.split(",");
        if (tokens.length < 3) {
            log.warn("[CsvTransformer] 잘못된 포맷의 라인 스킵: {}", csvLine);
            return null;
        }

        UserImportDto dto = UserImportDto.builder()
                .id(tokens[0].trim())
                .username(tokens[1].trim())
                .email(tokens[2].trim())
                .build();

        log.debug("[CsvTransformer] DTO 변환 성공: {}", dto);
        return dto;
    }
}
