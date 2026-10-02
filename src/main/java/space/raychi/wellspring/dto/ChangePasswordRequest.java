package space.raychi.wellspring.dto;

public record ChangePasswordRequest(String currentPassword, String newPassword) {}
