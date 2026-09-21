package com.petever.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String email;
    @Column(name = "password_hash") public String passwordHash;
    public String nickname;
    public String phone;
    @Column(name = "system_role") public String systemRole = "USER";
    public String status = "ACTIVE";
}
