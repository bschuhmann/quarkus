package io.quarkus.hibernate.orm.panache.deployment.test;

import jakarta.persistence.Entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class MyOtherEntity extends PanacheEntity {
    public String name;
}
