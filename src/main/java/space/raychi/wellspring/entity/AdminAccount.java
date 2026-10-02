package space.raychi.wellspring.entity;

public record AdminAccount(String username, String passwordHash, long credentialVersion) {}
