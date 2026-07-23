-- DB ➔ SFTP Export 테스트용 데이터 적재
INSERT INTO user_export (username, email, status) VALUES 
('hong_gildong', 'gildong@example.com', 'PENDING'),
('lee_sunsin', 'sunsin@example.com', 'PENDING'),
('sejong_daewang', 'sejong@example.com', 'PENDING')
ON CONFLICT DO NOTHING;
