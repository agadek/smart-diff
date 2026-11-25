CREATE TABLE `users` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `email` VARCHAR(255) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

DELIMITER //
CREATE PROCEDURE touch(IN user_id INT)
BEGIN
    UPDATE users SET email = LOWER(email) WHERE id = user_id;
END //
DELIMITER ;
