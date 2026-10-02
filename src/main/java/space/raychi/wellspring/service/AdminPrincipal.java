package space.raychi.wellspring.service;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

public class AdminPrincipal extends User {
    private final long credentialVersion;
    public AdminPrincipal(String username, String hash, long version) {
        super(username, hash, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        this.credentialVersion = version;
    }
    public long credentialVersion() { return credentialVersion; }
}
