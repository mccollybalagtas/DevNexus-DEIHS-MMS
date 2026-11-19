-- =====================================================================
--  DEIHS Maintenance Management System
--  Database schema + seed data
--  Target: MySQL 8.x / MariaDB 10.4+ (XAMPP)
--
--  Import:
--    mysql -u root -p < deihs_mms.sql
--  or in phpMyAdmin: Import > choose file > Go
--
--  Default accounts (change the passwords after first login):
--    admin   / admin123    School Administrator
--    mhead   / head123     Maintenance Head
--    tech1   / tech123     Maintenance Personnel
--    tech2   / tech123     Maintenance Personnel
--    jcruz   / staff123    Teacher
--    msantos / staff123    Teacher
-- =====================================================================

DROP DATABASE IF EXISTS deihs_mms;
CREATE DATABASE deihs_mms
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
USE deihs_mms;

-- ---------------------------------------------------------------------
-- 1. roles
-- ---------------------------------------------------------------------
CREATE TABLE roles (
    role_id     INT AUTO_INCREMENT PRIMARY KEY,
    role_code   VARCHAR(20)  NOT NULL UNIQUE,
    role_name   VARCHAR(60)  NOT NULL,
    description VARCHAR(255) NULL
) ENGINE=InnoDB;

INSERT INTO roles (role_code, role_name, description) VALUES
('ADMIN',     'School Administrator',  'Full access: users, facilities, equipment, reports, audit log'),
('MAINT_HEAD','Maintenance Head',      'Assigns work, manages schedules, reviews repair records and reports'),
('PERSONNEL', 'Maintenance Personnel', 'Works on assigned tasks and files repair records'),
('STAFF',     'Teacher / School Staff','Submits maintenance requests and tracks their status');

-- ---------------------------------------------------------------------
-- 2. users
-- ---------------------------------------------------------------------
CREATE TABLE users (
    user_id       INT AUTO_INCREMENT PRIMARY KEY,
    employee_no   VARCHAR(30)  NOT NULL UNIQUE,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    first_name    VARCHAR(60)  NOT NULL,
    last_name     VARCHAR(60)  NOT NULL,
    email         VARCHAR(120) NULL,
    phone         VARCHAR(30)  NULL,
    department    VARCHAR(80)  NULL,
    role_id       INT          NOT NULL,
    is_active     TINYINT(1)   NOT NULL DEFAULT 1,
    last_login    DATETIME     NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_users_role FOREIGN KEY (role_id) REFERENCES roles(role_id),
    INDEX idx_users_role (role_id),
    INDEX idx_users_active (is_active)
) ENGINE=InnoDB;

INSERT INTO users
(employee_no, username, password_hash, first_name, last_name, email, department, role_id)
VALUES
('EMP-0001', 'admin',  '$2a$10$YHP7aYbKCvHO8hgPwxa/r.YkitvLocyKy9OP9CSmocsC0ZqrroJ0.', 'Juan',   'Cruz',   'admin@deihs.edu.ph',   'Administration', 1),
('EMP-0002', 'mhead',  '$2a$10$wBYcUH1lSmy0i.9Z0p3tzO1WMdguVm/SwdVvQ6f7Sjpln6P31Onmu', 'Maria',  'Santos', 'mhead@deihs.edu.ph',   'Maintenance',    2),
('EMP-0003', 'tech1',  '$2a$10$YDts0jcGkq6hEHC5Mnlsou50lCKkCZ0kgrKKZEjfpialjcXnSvqzy', 'Pedro',  'Reyes',  'pedro@deihs.edu.ph',   'Maintenance',    3),
('EMP-0004', 'tech2',  '$2a$10$YDts0jcGkq6hEHC5Mnlsou50lCKkCZ0kgrKKZEjfpialjcXnSvqzy', 'Ana',    'Garcia', 'ana@deihs.edu.ph',     'Maintenance',    3),
('EMP-0005', 'jcruz',  '$2a$10$YZhC6udSxEj.HGyCK2Li2.iLxV9xO0S7ZyVXGGY42C44gPpoJmIS.', 'Jose',   'Cruz',   'jcruz@deihs.edu.ph',   'Science',        4),
('EMP-0006', 'msantos','$2a$10$YZhC6udSxEj.HGyCK2Li2.iLxV9xO0S7ZyVXGGY42C44gPpoJmIS.', 'Miguel', 'Santos', 'msantos@deihs.edu.ph', 'Mathematics',    4);

-- ---------------------------------------------------------------------
-- 3. maintenance_categories
-- ---------------------------------------------------------------------
CREATE TABLE maintenance_categories (
    category_id      INT AUTO_INCREMENT PRIMARY KEY,
    category_code    VARCHAR(20)  NOT NULL UNIQUE,
    name             VARCHAR(80)  NOT NULL,
    description      VARCHAR(255) NULL,
    default_priority ENUM('LOW','MEDIUM','HIGH','CRITICAL') NOT NULL DEFAULT 'MEDIUM',
    is_active        TINYINT(1)   NOT NULL DEFAULT 1
) ENGINE=InnoDB;

INSERT INTO maintenance_categories (category_code, name, description, default_priority) VALUES
('ELEC',  'Electrical',        'Wiring, lights, outlets, fans, breakers',        'HIGH'),
('PLUMB', 'Plumbing',          'Pipes, faucets, toilets, drainage, leaks',       'HIGH'),
('CARP',  'Carpentry',         'Doors, windows, desks, chairs, cabinets',        'MEDIUM'),
('AC',    'Air Conditioning',  'Aircon units, ventilation, cooling',             'HIGH'),
('ICT',   'Equipment / ICT',   'Computers, monitors, projectors, printers',      'MEDIUM'),
('PAINT', 'Painting',          'Walls, ceilings, repainting works',              'LOW'),
('ROOF',  'Roofing',           'Roof leaks, gutters, ceiling boards',            'HIGH'),
('GEN',   'General Maintenance','Cleaning, grounds, miscellaneous repairs',      'LOW');

-- ---------------------------------------------------------------------
-- 4. facilities
-- ---------------------------------------------------------------------
CREATE TABLE facilities (
    facility_id      INT AUTO_INCREMENT PRIMARY KEY,
    facility_code    VARCHAR(20)  NOT NULL UNIQUE,
    name             VARCHAR(120) NOT NULL,
    building         VARCHAR(80)  NULL,
    floor            VARCHAR(20)  NULL,
    room_number      VARCHAR(20)  NULL,
    facility_type    VARCHAR(60)  NULL,
    condition_status ENUM('GOOD','FAIR','POOR','NEEDS_REPAIR','OUT_OF_ORDER') NOT NULL DEFAULT 'GOOD',
    notes            TEXT         NULL,
    is_active        TINYINT(1)   NOT NULL DEFAULT 1,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_fac_building (building),
    INDEX idx_fac_condition (condition_status)
) ENGINE=InnoDB;

INSERT INTO facilities (facility_code, name, building, floor, room_number, facility_type, condition_status) VALUES
('RM-101', 'Room 101',            'Building A', '1st', '101', 'Classroom',   'GOOD'),
('RM-102', 'Room 102',            'Building A', '1st', '102', 'Classroom',   'GOOD'),
('RM-201', 'Room 201',            'Building A', '2nd', '201', 'Classroom',   'FAIR'),
('RM-204', 'Room 204',            'Building A', '2nd', '204', 'Classroom',   'FAIR'),
('LAB-1',  'Computer Laboratory 1','Building B','1st', 'B-101','Laboratory', 'GOOD'),
('LAB-2',  'Science Laboratory',  'Building B', '2nd', 'B-201','Laboratory',  'FAIR'),
('LIB',    'Library',             'Building C', '1st', 'C-101','Library',     'GOOD'),
('CR-1',   'Comfort Room 1',      'Building A', '1st', 'CR-1','Restroom',     'POOR'),
('CR-2',   'Comfort Room 2',      'Building A', '2nd', 'CR-2','Restroom',     'NEEDS_REPAIR'),
('GYM',    'Covered Court',       'Grounds',    '-',   '-',   'Sports Facility','GOOD'),
('OFF-ADM','Administration Office','Building C','1st', 'C-001','Office',     'GOOD'),
('CANTEEN','Canteen',             'Grounds',    '-',   '-',   'Canteen',     'FAIR');

-- ---------------------------------------------------------------------
-- 5. equipment
-- ---------------------------------------------------------------------
CREATE TABLE equipment (
    equipment_id     INT AUTO_INCREMENT PRIMARY KEY,
    property_no      VARCHAR(40)  NOT NULL UNIQUE,
    name             VARCHAR(120) NOT NULL,
    category         VARCHAR(60)  NULL,
    brand            VARCHAR(60)  NULL,
    model            VARCHAR(60)  NULL,
    serial_no        VARCHAR(60)  NULL,
    facility_id      INT          NULL,
    condition_status ENUM('GOOD','FAIR','POOR','NEEDS_REPAIR','OUT_OF_ORDER') NOT NULL DEFAULT 'GOOD',
    acquisition_date DATE         NULL,
    notes            TEXT         NULL,
    is_active        TINYINT(1)   NOT NULL DEFAULT 1,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_equip_facility FOREIGN KEY (facility_id) REFERENCES facilities(facility_id) ON DELETE SET NULL,
    INDEX idx_equip_facility (facility_id),
    INDEX idx_equip_condition (condition_status)
) ENGINE=InnoDB;

INSERT INTO equipment (property_no, name, category, brand, model, serial_no, facility_id, condition_status, acquisition_date) VALUES
('PROP-1001', 'Ceiling Fan',        'Electrical', 'Kdk',      'M40',      'KDK-88341', 4, 'FAIR',         '2021-06-15'),
('PROP-1002', 'Desktop Computer',   'ICT',        'Acer',     'Veriton',  'ACR-55210', 5, 'GOOD',         '2023-01-10'),
('PROP-1003', 'Desktop Computer',   'ICT',        'Acer',     'Veriton',  'ACR-55211', 5, 'NEEDS_REPAIR', '2023-01-10'),
('PROP-1004', 'LED Projector',      'ICT',        'Epson',    'EB-X06',   'EPS-11902', 1, 'GOOD',         '2022-08-20'),
('PROP-1005', 'Split-Type Aircon',  'Air Conditioning','Carrier','CS-12', 'CAR-77310', 7, 'FAIR',         '2020-11-05'),
('PROP-1006', 'Wall Fan',           'Electrical', 'Camel',    'CW-18',    'CML-33221', 2, 'GOOD',         '2022-03-12'),
('PROP-1007', 'Printer',            'ICT',        'Epson',    'L3210',    'EPS-90211', 11,'GOOD',         '2023-05-18'),
('PROP-1008', 'Water Dispenser',    'Appliance',  'Sharp',    'SWD-55',   'SHP-44501', 11,'FAIR',         '2021-09-30');

-- ---------------------------------------------------------------------
-- 6. maintenance_requests
-- ---------------------------------------------------------------------
CREATE TABLE maintenance_requests (
    request_id       INT AUTO_INCREMENT PRIMARY KEY,
    ticket_no        VARCHAR(20)  NOT NULL UNIQUE,
    requester_id     INT          NOT NULL,
    facility_id      INT          NULL,
    equipment_id     INT          NULL,
    category_id      INT          NOT NULL,
    title            VARCHAR(150) NOT NULL,
    description      TEXT         NULL,
    priority         ENUM('LOW','MEDIUM','HIGH','CRITICAL') NOT NULL DEFAULT 'MEDIUM',
    status           ENUM('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD','COMPLETED','CLOSED','REJECTED','CANCELLED')
                                  NOT NULL DEFAULT 'PENDING',
    assigned_to      INT          NULL,
    reported_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    target_date      DATE         NULL,
    completed_at     DATETIME     NULL,
    closed_at        DATETIME     NULL,
    resolution_notes TEXT         NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_req_requester FOREIGN KEY (requester_id) REFERENCES users(user_id),
    CONSTRAINT fk_req_facility  FOREIGN KEY (facility_id)  REFERENCES facilities(facility_id) ON DELETE SET NULL,
    CONSTRAINT fk_req_equipment FOREIGN KEY (equipment_id) REFERENCES equipment(equipment_id) ON DELETE SET NULL,
    CONSTRAINT fk_req_category  FOREIGN KEY (category_id)  REFERENCES maintenance_categories(category_id),
    CONSTRAINT fk_req_assigned  FOREIGN KEY (assigned_to)  REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_req_status (status),
    INDEX idx_req_priority (priority),
    INDEX idx_req_requester (requester_id),
    INDEX idx_req_assigned (assigned_to),
    INDEX idx_req_reported (reported_at)
) ENGINE=InnoDB;

INSERT INTO maintenance_requests
(ticket_no, requester_id, facility_id, equipment_id, category_id, title, description, priority, status, assigned_to, reported_at, target_date, completed_at)
VALUES
('T-0001', 5, 4, 1, 1, 'Ceiling fan not spinning',
 'The ceiling fan in Room 204 stopped spinning yesterday afternoon. It hums but the blades do not rotate. Class is using stand fans temporarily.',
 'HIGH', 'PENDING', NULL, '2026-11-11 08:12:00', '2026-11-13', NULL),
('T-0002', 6, 5, 3, 5, 'Computer monitor has no display',
 'Workstation 3 in Computer Laboratory 1 powers on but the monitor shows no signal. Tried a different cable, still nothing.',
 'MEDIUM', 'IN_PROGRESS', 3, '2026-11-10 09:40:00', '2026-11-12', NULL),
('T-0003', 5, 9, NULL, 2, 'Leaking pipe under sink',
 'Water is pooling under the sink in CR-2. The leak appears to come from the supply line connector.',
 'CRITICAL', 'ASSIGNED', 4, '2026-11-08 14:05:00', '2026-11-09', NULL),
('T-0004', 6, 7, 5, 4, 'Aircon not cooling',
 'The library aircon runs but does not cool. Room temperature stays around 30 degrees.',
 'HIGH', 'COMPLETED', 3, '2026-11-05 10:20:00', '2026-11-07', '2026-11-06 15:30:00'),
('T-0005', 5, 3, NULL, 3, 'Broken window latch',
 'One window in Room 201 cannot be locked because the latch is broken. Security concern after class hours.',
 'MEDIUM', 'CLOSED', 4, '2026-10-28 11:15:00', '2026-10-30', '2026-10-29 16:00:00');

-- ---------------------------------------------------------------------
-- 7. request_status_history
-- ---------------------------------------------------------------------
CREATE TABLE request_status_history (
    history_id  INT AUTO_INCREMENT PRIMARY KEY,
    request_id  INT          NOT NULL,
    from_status VARCHAR(20)  NULL,
    to_status   VARCHAR(20)  NOT NULL,
    changed_by  INT          NOT NULL,
    remarks     VARCHAR(255) NULL,
    changed_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_hist_request FOREIGN KEY (request_id) REFERENCES maintenance_requests(request_id) ON DELETE CASCADE,
    CONSTRAINT fk_hist_user    FOREIGN KEY (changed_by) REFERENCES users(user_id),
    INDEX idx_hist_request (request_id)
) ENGINE=InnoDB;

INSERT INTO request_status_history (request_id, from_status, to_status, changed_by, remarks, changed_at) VALUES
(1, NULL,        'PENDING',     5, 'Request submitted',                     '2026-11-11 08:12:00'),
(2, NULL,        'PENDING',     6, 'Request submitted',                     '2026-11-10 09:40:00'),
(2, 'PENDING',   'ASSIGNED',    2, 'Assigned to Pedro Reyes',               '2026-11-10 10:15:00'),
(2, 'ASSIGNED',  'IN_PROGRESS', 3, 'Started diagnostics on workstation 3',  '2026-11-10 13:00:00'),
(3, NULL,        'PENDING',     5, 'Request submitted',                     '2026-11-08 14:05:00'),
(3, 'PENDING',   'ASSIGNED',    2, 'Assigned to Ana Garcia - urgent',       '2026-11-08 14:30:00'),
(4, NULL,        'PENDING',     6, 'Request submitted',                     '2026-11-05 10:20:00'),
(4, 'PENDING',   'ASSIGNED',    2, 'Assigned to Pedro Reyes',               '2026-11-05 11:00:00'),
(4, 'ASSIGNED',  'IN_PROGRESS', 3, 'Checked refrigerant level',             '2026-11-05 14:00:00'),
(4, 'IN_PROGRESS','COMPLETED',  3, 'Recharged refrigerant, cleaned filter','2026-11-06 15:30:00'),
(5, NULL,        'PENDING',     5, 'Request submitted',                     '2026-10-28 11:15:00'),
(5, 'PENDING',   'ASSIGNED',    2, 'Assigned to Ana Garcia',                '2026-10-28 13:00:00'),
(5, 'ASSIGNED',  'IN_PROGRESS', 4, 'Replaced latch',                        '2026-10-29 09:00:00'),
(5, 'IN_PROGRESS','COMPLETED',  4, 'Latch replaced and tested',             '2026-10-29 16:00:00'),
(5, 'COMPLETED', 'CLOSED',      1, 'Confirmed by requester',                '2026-10-30 08:30:00');

-- ---------------------------------------------------------------------
-- 8. maintenance_records
-- ---------------------------------------------------------------------
CREATE TABLE maintenance_records (
    record_id      INT AUTO_INCREMENT PRIMARY KEY,
    request_id     INT           NOT NULL,
    personnel_id   INT           NOT NULL,
    actions_taken  TEXT          NOT NULL,
    parts_used     TEXT          NULL,
    cost           DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    downtime_hours DECIMAL(6,2)  NULL,
    started_at     DATETIME      NULL,
    finished_at    DATETIME      NULL,
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rec_request   FOREIGN KEY (request_id)   REFERENCES maintenance_requests(request_id) ON DELETE CASCADE,
    CONSTRAINT fk_rec_personnel FOREIGN KEY (personnel_id) REFERENCES users(user_id),
    INDEX idx_rec_request (request_id)
) ENGINE=InnoDB;

INSERT INTO maintenance_records
(request_id, personnel_id, actions_taken, parts_used, cost, downtime_hours, started_at, finished_at)
VALUES
(4, 3, 'Checked refrigerant pressure, found low charge. Recharged refrigerant and cleaned the air filter. Tested cooling for 30 minutes.',
 'R-32 refrigerant 0.5kg, air filter', 1250.00, 4.50, '2026-11-05 14:00:00', '2026-11-06 15:30:00'),
(5, 4, 'Removed the broken window latch and installed a new stainless latch. Adjusted the window frame alignment.',
 'Stainless window latch', 180.00, 1.50, '2026-10-29 09:00:00', '2026-10-29 16:00:00');

-- ---------------------------------------------------------------------
-- 9. attachments
-- ---------------------------------------------------------------------
CREATE TABLE attachments (
    attachment_id INT AUTO_INCREMENT PRIMARY KEY,
    request_id    INT          NOT NULL,
    file_name     VARCHAR(255) NOT NULL,
    file_path     VARCHAR(500) NOT NULL,
    file_size     BIGINT       NULL,
    mime_type     VARCHAR(100) NULL,
    uploaded_by   INT          NOT NULL,
    uploaded_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_att_request FOREIGN KEY (request_id) REFERENCES maintenance_requests(request_id) ON DELETE CASCADE,
    CONSTRAINT fk_att_user    FOREIGN KEY (uploaded_by) REFERENCES users(user_id),
    INDEX idx_att_request (request_id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 10. maintenance_schedules
-- ---------------------------------------------------------------------
CREATE TABLE maintenance_schedules (
    schedule_id    INT AUTO_INCREMENT PRIMARY KEY,
    facility_id    INT          NULL,
    equipment_id   INT          NULL,
    title          VARCHAR(150) NOT NULL,
    description    TEXT         NULL,
    frequency      ENUM('DAILY','WEEKLY','MONTHLY','QUARTERLY','SEMI_ANNUAL','ANNUAL') NOT NULL DEFAULT 'MONTHLY',
    next_due_date  DATE         NOT NULL,
    last_done_date DATE         NULL,
    assigned_to    INT          NULL,
    is_active      TINYINT(1)   NOT NULL DEFAULT 1,
    created_by     INT          NOT NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_sched_facility  FOREIGN KEY (facility_id)  REFERENCES facilities(facility_id) ON DELETE CASCADE,
    CONSTRAINT fk_sched_equipment FOREIGN KEY (equipment_id) REFERENCES equipment(equipment_id) ON DELETE CASCADE,
    CONSTRAINT fk_sched_assigned  FOREIGN KEY (assigned_to)  REFERENCES users(user_id) ON DELETE SET NULL,
    CONSTRAINT fk_sched_creator   FOREIGN KEY (created_by)   REFERENCES users(user_id),
    INDEX idx_sched_due (next_due_date),
    INDEX idx_sched_active (is_active)
) ENGINE=InnoDB;

INSERT INTO maintenance_schedules
(facility_id, equipment_id, title, description, frequency, next_due_date, last_done_date, assigned_to, created_by)
VALUES
(5, NULL, 'Computer Lab 1 cleaning and cable check', 'Dust out workstations, check cables and network drops.', 'MONTHLY', '2026-12-01', '2026-11-01', 3, 2),
(7, 5,   'Library aircon filter cleaning',           'Remove and wash the air filter, check drainage.',        'MONTHLY', '2026-12-05', '2026-11-05', 3, 2),
(NULL, 2, 'Workstation preventive check',            'Run diagnostics, update antivirus, check storage.',      'QUARTERLY','2027-01-10', '2026-10-10', 3, 2),
(8, NULL,'Comfort Room 1 deep cleaning',             'Descale fixtures, check drainage and ventilation.',      'WEEKLY',  '2026-11-16', '2026-11-09', 4, 2),
(10, NULL,'Covered court lighting inspection',       'Check all lamps, ballasts and wiring.',                  'SEMI_ANNUAL','2027-04-01','2026-10-01', 4, 2);

-- ---------------------------------------------------------------------
-- 11. audit_logs
-- ---------------------------------------------------------------------
CREATE TABLE audit_logs (
    log_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     INT          NULL,
    action      VARCHAR(80)  NOT NULL,
    entity_type VARCHAR(60)  NULL,
    entity_id   VARCHAR(40)  NULL,
    details     TEXT         NULL,
    ip_address  VARCHAR(45)  NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_log_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_log_user (user_id),
    INDEX idx_log_created (created_at)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 12. request_comments
-- ---------------------------------------------------------------------
CREATE TABLE request_comments (
    comment_id   INT AUTO_INCREMENT PRIMARY KEY,
    request_id   INT      NOT NULL,
    user_id      INT      NOT NULL,
    comment_text TEXT     NOT NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cmt_request FOREIGN KEY (request_id) REFERENCES maintenance_requests(request_id) ON DELETE CASCADE,
    CONSTRAINT fk_cmt_user    FOREIGN KEY (user_id)    REFERENCES users(user_id),
    INDEX idx_cmt_request (request_id)
) ENGINE=InnoDB;

INSERT INTO request_comments (request_id, user_id, comment_text, created_at) VALUES
(1, 5, 'Still not fixed, class is getting warm in the afternoon.', '2026-11-11 13:20:00'),
(2, 6, 'Please check the display cable as well, we already tried a different monitor.', '2026-11-10 11:00:00'),
(3, 2, 'Priority raised to critical. Please bring a pipe wrench and replacement connector.', '2026-11-08 14:32:00');

-- ---------------------------------------------------------------------
-- 12. account_requests (self-registration, pending admin approval)
-- ---------------------------------------------------------------------
CREATE TABLE account_requests (
    request_id      INT AUTO_INCREMENT PRIMARY KEY,
    employee_no     VARCHAR(30)  NOT NULL,
    username        VARCHAR(50)  NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    first_name      VARCHAR(60)  NOT NULL,
    last_name       VARCHAR(60)  NOT NULL,
    email           VARCHAR(120) NULL,
    phone           VARCHAR(30)  NULL,
    department      VARCHAR(80)  NULL,
    requested_role  VARCHAR(20)  NOT NULL,
    status          ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
    reviewed_by     INT          NULL,
    reviewed_at     DATETIME     NULL,
    review_notes    VARCHAR(255) NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_acctreq_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_acctreq_status (status),
    INDEX idx_acctreq_username (username)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Views used by dashboards / reports
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW vw_request_overview AS
SELECT
    r.request_id,
    r.ticket_no,
    r.title,
    r.priority,
    r.status,
    r.reported_at,
    r.target_date,
    CONCAT(req.first_name, ' ', req.last_name) AS requester_name,
    COALESCE(f.name, e.name, '—')              AS asset_name,
    c.name                                     AS category_name,
    CONCAT(asg.first_name, ' ', asg.last_name) AS assigned_name,
    CASE
        WHEN r.status IN ('PENDING','ASSIGNED','IN_PROGRESS','ON_HOLD')
             AND r.target_date IS NOT NULL
             AND r.target_date < CURDATE()
        THEN 1 ELSE 0
    END AS is_overdue
FROM maintenance_requests r
JOIN users req              ON req.user_id = r.requester_id
JOIN maintenance_categories c ON c.category_id = r.category_id
LEFT JOIN facilities f      ON f.facility_id = r.facility_id
LEFT JOIN equipment  e      ON e.equipment_id = r.equipment_id
LEFT JOIN users asg         ON asg.user_id = r.assigned_to;

-- ---------------------------------------------------------------------
-- Done
-- ---------------------------------------------------------------------
SELECT 'DEIHS MMS database created successfully.' AS result;
