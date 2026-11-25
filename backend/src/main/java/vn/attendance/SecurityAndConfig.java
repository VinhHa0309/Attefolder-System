package vn.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

@Configuration
class AppConfiguration {
    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    WebMvcConfigurer cors(@Value("${app.web-url}") String webUrl) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(webUrl)
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("Authorization", "Content-Type")
                        .maxAge(3600);
            }
        };
    }

    @Bean
    ApplicationRunner bootstrap(
            JdbcTemplate db,
            BCryptPasswordEncoder encoder,
            @Value("${app.bootstrap.email}") String email,
            @Value("${app.bootstrap.password}") String password) {
        return args -> {
            Integer count = db.queryForObject("SELECT count(*) FROM app_user WHERE role = 'SYSTEM_ADMIN'", Integer.class);
            if (count == null || count == 0) {
                db.update(
                        "INSERT INTO app_user(email, full_name, password_hash, role, status, email_verified) VALUES(?,?,?,'SYSTEM_ADMIN','ACTIVE',true)",
                        email.toLowerCase(Locale.ROOT), "Quản trị hệ thống", encoder.encode(password));
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "app.demo-data", havingValue = "true", matchIfMissing = true)
    ApplicationRunner demoData(JdbcTemplate db, BCryptPasswordEncoder encoder) {
        return args -> {
            Integer existingSchools = db.queryForObject("SELECT count(*) FROM school", Integer.class);
            if (existingSchools != null && existingSchools > 0) {
                return;
            }
            String pass = encoder.encode("CampusDemo2026!");
            String[] codes = {"DEMO-HN", "DEMO-HCM"};
            String[] names = {"Đại học Campus Hà Nội", "Đại học Campus Sài Gòn"};
            double[] latitudes = {21.0285, 10.7626};
            double[] longitudes = {105.8542, 106.6602};
            for (int i = 0; i < 2; i++) {
                UUID school = UUID.randomUUID();
                UUID admin = UUID.randomUUID();
                UUID lecturer = UUID.randomUUID();
                UUID student = UUID.randomUUID();
                UUID clazz = UUID.randomUUID();
                String adminEmail = "admin" + (i + 1) + "@campus.test";
                String studentNo = String.format("00%d12345", i + 1);
                String studentEmail = "student" + (i + 1) + "@campus.test";

                db.update(
                        "INSERT INTO app_user(id, email, recovery_email, full_name, password_hash, role, status, must_change_password, email_verified) VALUES(?,?,?,?,?,'SCHOOL_ADMIN','ACTIVE',false,true)",
                        admin, adminEmail, adminEmail, "Admin trường " + (i + 1), pass);
                db.update(
                        "INSERT INTO school(id, code, name, admin_user_id, recovery_email, campus_lat, campus_lng, allowed_cidrs) VALUES(?,?,?,?,?,?,?,array['10.0.0.0/8'::cidr,'192.168.0.0/16'::cidr])",
                        school, codes[i], names[i], admin, adminEmail, latitudes[i], longitudes[i]);
                db.update("UPDATE app_user SET school_id = ? WHERE id = ?", school, admin);
                db.update(
                        "INSERT INTO app_user(id, school_id, email, full_name, gender, date_of_birth, password_hash, role, status, email_verified) VALUES(?,NULL,?,?,?,?,?,'LECTURER','ACTIVE',true)",
                        lecturer, "lecturer" + (i + 1) + "@campus.test", "Giảng viên Demo " + (i + 1), "MALE", java.sql.Date.valueOf("1990-05-15"), pass);
                db.update(
                        "INSERT INTO app_user(id, school_id, student_no, email, full_name, date_of_birth, password_hash, role, status, must_change_password, email_verified) VALUES(?,?,?,?,?,?,?,'STUDENT','ACTIVE',false,true)",
                        student, school, studentNo, studentEmail, "Nguyễn Minh Anh", java.sql.Date.valueOf("2005-09-03"), pass);
                db.update(
                        "INSERT INTO identity_import_row(school_id, student_no, full_name, school_email, date_of_birth) VALUES(?,?,?,?,?)",
                        school, studentNo, "Nguyễn Minh Anh", studentEmail, java.sql.Date.valueOf("2005-09-03"));
                String staticQr = SecurityUtil.randomToken(16);
                db.update(
                        "INSERT INTO course_class(id, school_id, lecturer_id, name, code, term, school_year, static_qr_token) VALUES(?,?,?,?,?,?,?,?)",
                        clazz, school, lecturer, "Nhập môn hệ thống thông tin", "HTTT10" + (i + 1), "Học kỳ I", "2026–2027", staticQr);
                db.update("INSERT INTO class_whitelist(class_id, student_no) VALUES(?,?)", clazz, studentNo);
                db.update("INSERT INTO class_student(class_id, student_id) VALUES(?,?)", clazz, student);
            }
        };
    }
}

final class SecurityUtil {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TEMP_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$";

    private SecurityUtil() {}

    static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hmacSha256(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String randomToken(int byteCount) {
        byte[] b = new byte[byteCount];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String randomOtp6() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    static String randomTempPassword() {
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 12; i++) {
            sb.append(TEMP_CHARS.charAt(RANDOM.nextInt(TEMP_CHARS.length())));
        }
        sb.append("A1!");
        return sb.toString();
    }

    static Instant toInstant(Object value) {
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof OffsetDateTime t) return t.toInstant();
        if (value instanceof Instant t) return t;
        throw new IllegalStateException("Unexpected timestamp type: " + value);
    }

    static String pgTextArray(List<String> values) {
        return "{" + String.join(",", values) + "}";
    }

    static String[] textArray(Object value) {
        try {
            if (value instanceof Array a) return (String[]) a.getArray();
            if (value instanceof String[] a) return a;
            return new String[0];
        } catch (Exception e) {
            return new String[0];
        }
    }

    static String clientIp(HttpServletRequest req, String trustedProxies) {
        String remote = req.getRemoteAddr();
        if (!isTrustedProxy(remote, trustedProxies)) {
            return remote;
        }
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remote;
        }
        List<String> chain = new ArrayList<>(Arrays.asList(forwarded.split(",")));
        chain.add(remote);
        for (int i = chain.size() - 1; i >= 0; i--) {
            String candidate = chain.get(i).trim();
            if (!candidate.isEmpty() && !isTrustedProxy(candidate, trustedProxies)) {
                return candidate;
            }
        }
        return remote;
    }

    static boolean isTrustedProxy(String ip, String trustedProxies) {
        if (ip == null || trustedProxies == null) return false;
        for (String cidr : trustedProxies.split(",")) {
            String trimmed = cidr.trim();
            if (trimmed.isEmpty()) continue;
            if (ipMatchesCidr(ip, trimmed)) return true;
        }
        return false;
    }

    static boolean ipMatchesCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            byte[] addr = InetAddress.getByName(ip).getAddress();
            byte[] net = InetAddress.getByName(parts[0]).getAddress();
            if (addr.length != net.length) return false;
            int bits = parts.length == 1 ? addr.length * 8 : Integer.parseInt(parts[1]);
            for (int i = 0; i < addr.length; i++) {
                int n = Math.min(8, bits);
                int mask = n == 0 ? 0 : (0xff << (8 - n)) & 0xff;
                if ((addr[i] & mask) != (net[i] & mask)) return false;
                bits -= n;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static String uid(HttpServletRequest req) {
        Object x = req.getAttribute("uid");
        if (x == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
        return x.toString();
    }

    static String role(HttpServletRequest req) {
        uid(req);
        return req.getAttribute("role").toString();
    }

    static String schoolId(HttpServletRequest req) {
        Object x = req.getAttribute("schoolId");
        return x == null ? null : x.toString();
    }

    static void requireRole(HttpServletRequest req, String... roles) {
        String current = role(req);
        if (Arrays.stream(roles).noneMatch(current::equals)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không đủ quyền thực hiện");
        }
    }

    static Map<String, Object> one(JdbcTemplate db, String sql, Object... args) {
        List<Map<String, Object>> rows = db.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String visible = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return visible + "***" + email.substring(at);
    }
}

@Service
class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final JdbcTemplate db;
    private final String fromAddress;
    private final String smtpHost;
    private final boolean exposeDevMail;

    MailService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            JdbcTemplate db,
            @Value("${spring.mail.username:no-reply@campus.local}") String fromAddress,
            @Value("${spring.mail.host:}") String smtpHost,
            @Value("${app.expose-dev-mail:true}") boolean exposeDevMail) {
        this.mailSenderProvider = mailSenderProvider;
        this.db = db;
        this.fromAddress = (fromAddress == null || fromAddress.isBlank()) ? "no-reply@campus.local" : fromAddress;
        this.smtpHost = smtpHost == null ? "" : smtpHost.trim();
        this.exposeDevMail = exposeDevMail;
    }

    record DeliveryResult(boolean delivered, String devSecretPreview) {}

    DeliveryResult send(String to, String subject, String body, String devSecretValue) {
        boolean delivered = false;
        String errorMessage = null;
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender != null && !smtpHost.isEmpty()) {
            try {
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setFrom(fromAddress);
                msg.setTo(to);
                msg.setSubject(subject);
                msg.setText(body);
                sender.send(msg);
                delivered = true;
            } catch (Exception ex) {
                errorMessage = ex.getMessage();
                log.warn("SMTP delivery failed for {} ({}): {}", to, subject, ex.getMessage());
            }
        } else {
            errorMessage = "SMTP chưa cấu hình hoặc chưa chạy";
        }
        try {
            db.update(
                    "INSERT INTO dev_mail_outbox(recipient_email, subject, body, delivered, error_message) VALUES(?,?,?,?,?)",
                    to, subject, body, delivered, errorMessage);
        } catch (Exception e) {
            log.warn("Could not record outbox entry: {}", e.getMessage());
        }
        return new DeliveryResult(delivered, exposeDevMail ? devSecretValue : null);
    }

    boolean isExposeDevMail() {
        return exposeDevMail;
    }
}

@Component
class AuthFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/public/schools",
            "/api/auth/login",
            "/api/auth/activation-info",
            "/api/auth/activate",
            "/api/auth/lecturer/register",
            "/api/auth/lecturer/verify-otp",
            "/api/auth/lecturer/resend-otp",
            "/api/auth/forgot-password",
            "/api/auth/reset-password",
            "/api/auth/dev-outbox"
    );

    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();

    AuthFilter(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String uri = req.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true;
        if (!uri.startsWith("/api/")) return true;
        return PUBLIC_PATHS.contains(uri);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            writeError(res, 401, "Yêu cầu đăng nhập trước khi sử dụng chức năng này");
            return;
        }
        String token = auth.substring(7).trim();
        if (token.isEmpty()) {
            writeError(res, 401, "Phiên đăng nhập không hợp lệ");
            return;
        }
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT u.id, u.role, u.school_id, u.status, u.must_change_password FROM auth_token t JOIN app_user u ON u.id = t.user_id WHERE t.token_hash = ? AND t.expires_at > now()",
                SecurityUtil.sha256(token));
        if (rows.isEmpty()) {
            writeError(res, 401, "Phiên đăng nhập đã hết hạn hoặc không hợp lệ");
            return;
        }
        Map<String, Object> u = rows.get(0);
        String status = String.valueOf(u.get("status"));
        if ("INACTIVE".equals(status)) {
            writeError(res, 403, "Tài khoản chưa được kích hoạt");
            return;
        }
        if (!"ACTIVE".equals(status)) {
            writeError(res, 403, "Tài khoản đã bị khóa");
            return;
        }
        req.setAttribute("uid", u.get("id").toString());
        req.setAttribute("role", u.get("role").toString());
        req.setAttribute("schoolId", u.get("school_id") == null ? null : u.get("school_id").toString());
        req.setAttribute("mustChange", u.get("must_change_password"));

        String uri = req.getRequestURI();
        if (Boolean.TRUE.equals(u.get("must_change_password"))
                && !uri.equals("/api/auth/change-password")
                && !uri.equals("/api/auth/logout")
                && !uri.equals("/api/auth/me")) {
            writeError(res, 403, "Cần đổi mật khẩu lần đầu trước khi tiếp tục");
            return;
        }
        chain.doFilter(req, res);
    }

    private void writeError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write(json.writeValueAsString(Map.of("error", message)));
    }
}

@RestControllerAdvice
class Errors {
    private static final Logger log = LoggerFactory.getLogger(Errors.class);

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("error", e.getReason() == null ? "Yêu cầu không hợp lệ" : e.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> validation(MethodArgumentNotValidException e) {
        FieldError first = e.getBindingResult().getFieldError();
        String msg = first != null ? first.getField() + ": " + first.getDefaultMessage() : "Dữ liệu đầu vào không hợp lệ";
        return Map.of("error", msg);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> duplicate() {
        return Map.of("error", "Dữ liệu đã tồn tại");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    Map<String, String> other(Exception e) {
        log.error("Unhandled server error", e);
        return Map.of("error", "Lỗi hệ thống");
    }
}
