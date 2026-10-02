package space.raychi.wellspring.service;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.dto.ChangePasswordRequest;
import space.raychi.wellspring.mapper.AdminAccountMapper;

@Service
public class AdminAccountService {
    private final AdminAccountMapper accounts;
    private final PasswordEncoder passwords;
    public AdminAccountService(AdminAccountMapper accounts, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
    }

    public void initialize(String username, String hash) {
        if (accounts.select() != null) return;
        if (username.isBlank() || username.length() > 100 || !hash.matches("^\\$2[aby]\\$\\d\\d\\$[./A-Za-z0-9]{53}$")) {
            throw new IllegalStateException("Set RAYCHI_ADMIN_USER and a BCrypt RAYCHI_ADMIN_PASSWORD_HASH for first initialization.");
        }
        accounts.initialize(username, hash);
    }

    public AdminPrincipal loadUser(String username) {
        var account = accounts.select();
        if (account == null || !account.username().equals(username)) throw new UsernameNotFoundException("Unknown account");
        return new AdminPrincipal(account.username(), account.passwordHash(), account.credentialVersion());
    }

    public boolean isCurrent(AdminPrincipal principal) {
        return accounts.hasVersion(principal.getUsername(), principal.credentialVersion());
    }

    @Transactional
    public void changePassword(AdminPrincipal principal, ChangePasswordRequest input) {
        if (input == null || input.currentPassword() == null || input.currentPassword().isEmpty()
                || input.currentPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || input.newPassword() == null || input.newPassword().isBlank() || input.newPassword().codePointCount(0, input.newPassword().length()) < 12
                || input.newPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请输入当前密码；新密码至少 12 个字符，UTF-8 长度最多 72 字节。");
        }
        var account = accounts.select();
        if (account == null || !account.username().equals(principal.getUsername())
                || account.credentialVersion() != principal.credentialVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "PASSWORD_CHANGED", "密码已更改，请重新登录。");
        }
        if (!passwords.matches(input.currentPassword(), account.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INVALID", "当前密码不正确。");
        }
        if (passwords.matches(input.newPassword(), account.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "新密码不能与当前密码相同。");
        }
        if (accounts.changePassword(principal.getUsername(), account.credentialVersion(), passwords.encode(input.newPassword())) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "PASSWORD_CHANGED", "密码已更改，请重新登录。");
        }
    }
}
