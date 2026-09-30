-- DB ➔ SFTP Export 테스트용 데이터 적재
INSERT INTO user_export (username, email, status) VALUES 
('hong_gildong', 'gildong@example.com', 'PENDING'),
('lee_sunsin', 'sunsin@example.com', 'PENDING'),
('sejong_daewang', 'sejong@example.com', 'PENDING')
ON CONFLICT DO NOTHING;

-- admin / admin1! 초기 계정 생성 및 갱신 (검증된 BCrypt 해시: admin1!)
INSERT INTO app_user (username, password, role, enabled) VALUES
('admin', '$2a$10$//t6bqqX3LNU1AmuzNsk9uVpip/n9HHMQeEDVIpitzBUXV5cTUyE2', 'ROLE_ADMIN', true)
ON CONFLICT (username) DO UPDATE SET 
    password = EXCLUDED.password,
    role = EXCLUDED.role,
    enabled = EXCLUDED.enabled;
