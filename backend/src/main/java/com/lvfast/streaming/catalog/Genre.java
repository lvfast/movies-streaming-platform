package com.lvfast.streaming.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "genre")
public class Genre {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Short id;
    @Column(nullable = false, unique = true) private String slug;
    @Column(nullable = false, unique = true) private String name;

    protected Genre() {}
}
