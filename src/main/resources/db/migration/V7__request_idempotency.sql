-- Reserved and populated in the same transaction as the resource; never committed alone.
CREATE TABLE request_dedup (
    user_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (user_id, operation, key_hash)
) ENGINE=InnoDB;
