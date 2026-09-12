package dev.reception.auth;

import static net.logstash.logback.argument.StructuredArguments.kv;

import dev.reception.auth.RefreshTokenService.RequestFingerprint;
import dev.reception.business.Business;
import dev.reception.business.BusinessProvisioningService;
import dev.reception.business.BusinessRepository;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.ids.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login, refresh and logout.
 *
 * <p>The one thing here that is not conventional is registration's atomicity, and it is worth the
 * attention: an account is a {@code User}, a {@code Business}, an {@code OWNER} {@code Membership}
 * and a default opening week, and a failure at any step must leave zero rows. A half-created
 * account is worse than a failed one, because the owner cannot register again with the same
 * address and cannot sign in either.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);


    /** The constraint names the database reports, so an integrity error is mapped, not guessed. */
    private static final String EMAIL_CONSTRAINT = "users_email_unique";

    private static final String SLUG_CONSTRAINT = "businesses_slug_unique";

    private final UserRepository users;
    private final MembershipRepository memberships;
    private final BusinessRepository businesses;
    private final BusinessProvisioningService businessProvisioning;
    private final RefreshTokenService refreshTokens;
    private final JwtService jwt;
    private final PasswordEncoder passwordEncoder;
    private final IdGenerator ids;
    private final Clock clock;

    public AuthService(
            UserRepository users,
            MembershipRepository memberships,
            BusinessRepository businesses,
            BusinessProvisioningService businessProvisioning,
            RefreshTokenService refreshTokens,
            JwtService jwt,
            PasswordEncoder passwordEncoder,
            IdGenerator ids,
            Clock clock) {
        this.users = users;
        this.memberships = memberships;
        this.businesses = businesses;
        this.businessProvisioning = businessProvisioning;
        this.refreshTokens = refreshTokens;
        this.jwt = jwt;
        this.passwordEncoder = passwordEncoder;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Creates a user, their business, their owner membership and the default week — in one
     * transaction — and signs them in.
     */
    @Transactional
    public Session register(
            String email,
            String rawPassword,
            String fullName,
            String businessName,
            RequestFingerprint fingerprint) {
        String normalisedEmail = normalise(email);

        // Checked up front for a clear error; the unique index is what actually decides, because
        // two simultaneous registrations both pass this check.
        if (users.existsByEmail(normalisedEmail)) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN, "An account already exists for that email address.");
        }

        Instant now = clock.instant();
        User user = new User(ids.newId(), normalisedEmail, passwordEncoder.encode(rawPassword), fullName, now);
        Business business = businessProvisioning.provision(businessName);
        Membership membership = new Membership(ids.newId(), user.getId(), business.getId(), Role.OWNER, now);

        try {
            users.save(user);
            memberships.save(membership);
            // Forces every pending insert now rather than at commit, so a constraint violation is
            // raised here — where it can be mapped to an error code — instead of escaping the
            // transaction as an unhandled 500.
            users.flush();
        } catch (DataIntegrityViolationException e) {
            throw mapIntegrityViolation(e);
        }

        Session session = startSession(user, membership, business, fingerprint);
        record(Event.REGISTER, user.getId(), fingerprint);
        return session;
    }

    /**
     * Verifies credentials and starts a session.
     *
     * <p>Every failure returns the same {@code INVALID_CREDENTIALS}, and the password is verified
     * even when no user was found. Skipping the hash for an unknown address makes the endpoint
     * measurably faster for addresses that do not exist, which is a timing oracle for exactly the
     * question the identical message exists to hide.
     */
    @Transactional
    public Session login(String email, String rawPassword, RequestFingerprint fingerprint) {
        User user = users.findByEmail(normalise(email)).orElse(null);

        if (user == null) {
            passwordEncoder.matches(rawPassword, DUMMY_HASH);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
            throw invalidCredentials();
        }

        Membership membership = primaryMembershipOf(user);
        Business business = businesses
                .findById(membership.businessId())
                .orElseThrow(() -> new IllegalStateException(
                        "Membership " + membership.getId() + " references a business that does not exist"));

        Session session = startSession(user, membership, business, fingerprint);
        record(Event.LOGIN, user.getId(), fingerprint);
        return session;
    }

    /** Rotates the refresh token and mints a matching access token. */
    @Transactional
    public Session refresh(String presentedRefreshToken, RequestFingerprint fingerprint) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(presentedRefreshToken, fingerprint);

        User user = users.findById(rotation.userId())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.UNAUTHENTICATED, "That session is no longer valid. Please sign in again."));
        Membership membership = primaryMembershipOf(user);
        Business business = businesses
                .findById(membership.businessId())
                .orElseThrow(() -> new IllegalStateException(
                        "Membership " + membership.getId() + " references a business that does not exist"));

        JwtService.IssuedAccessToken accessToken =
                jwt.issue(user.getId(), business.getId(), membership.role());
        record(Event.REFRESH, user.getId(), fingerprint);
        return new Session(user, business, membership.role(), accessToken, rotation.refreshToken());
    }

    /** Idempotent: an absent, unknown or already-revoked token is a successful logout. */
    @Transactional
    public void logout(String presentedRefreshToken, RequestFingerprint fingerprint) {
        if (presentedRefreshToken != null && !presentedRefreshToken.isBlank()) {
            refreshTokens.revoke(presentedRefreshToken).ifPresent(userId -> record(Event.LOGOUT, userId, fingerprint));
        }
    }

    /** The current user, their business and their role — what {@code GET /auth/me} answers with. */
    @Transactional(readOnly = true)
    public Session describe(AuthenticatedUser principal) {
        User user = users.findById(principal.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Your account no longer exists."));
        Business business = businesses
                .findById(principal.businessId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Your business no longer exists."));
        return new Session(user, business, principal.role(), null, null);
    }

    /**
     * The authentication events docs/06-security.md §14 says are recorded.
     *
     * <p>Until phase 11 the sentence was true of none of them: nothing in this class logged
     * anything, and the only authentication line in the application was the replay warning in
     * {@link RefreshTokenFamilyRevoker}, which carried a user id and no address. An audit trail
     * that begins at the one event an attacker triggers deliberately is not an audit trail.
     *
     * <p><strong>The user id and not the email.</strong> The id is stable, it is what every other
     * record in the system joins on, and the address is a customer-grade identifier that
     * {@code PiiValueMasker} redacts out of log output anyway — so logging it would produce a line
     * that names nobody. The IP is the peer address, the same value the refresh token row already
     * stores, and it is deliberately not masked: an authentication record without an origin cannot
     * answer the question it exists for.
     */
    private void record(Event event, UUID userId, RequestFingerprint fingerprint) {
        log.info(
                "Authentication event: {} {} {}",
                kv("event", event.name().toLowerCase(Locale.ROOT)),
                kv("user_id", userId),
                kv("ip", fingerprint.ip()));
    }

    /**
     * {@code REGISTER} is here although §14 names only login, refresh and revocation: registration
     * is where a session first exists, and leaving it out puts the hole in the audit trail exactly
     * at the moment an account is created.
     */
    private enum Event {
        REGISTER,
        LOGIN,
        REFRESH,
        LOGOUT
    }

    private Session startSession(
            User user, Membership membership, Business business, RequestFingerprint fingerprint) {
        JwtService.IssuedAccessToken accessToken =
                jwt.issue(user.getId(), business.getId(), membership.role());
        String refreshToken = refreshTokens.issueNewFamily(user.getId(), fingerprint);
        return new Session(user, business, membership.role(), accessToken, refreshToken);
    }

    /**
     * MVP creates exactly one membership per user. When the model grows to several, this is the one
     * place that has to decide which business a session is for — so it is a method rather than an
     * inline {@code get(0)} scattered across three call sites.
     */
    private Membership primaryMembershipOf(User user) {
        List<Membership> found = memberships.findByUserId(user.getId());
        if (found.isEmpty()) {
            throw new IllegalStateException("User " + user.getId() + " has no membership");
        }
        return found.getFirst();
    }

    private ApiException mapIntegrityViolation(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains(EMAIL_CONSTRAINT)) {
            // The pre-check lost a race with a simultaneous registration.
            return new ApiException(ErrorCode.EMAIL_TAKEN, "An account already exists for that email address.");
        }
        if (message.contains(SLUG_CONSTRAINT)) {
            return new ApiException(
                    ErrorCode.SLUG_TAKEN, "That business name is momentarily unavailable. Please try again.");
        }
        // Keyed on the constraint name, so an unrelated integrity error is not masked as a 409.
        throw e;
    }

    private static ApiException invalidCredentials() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS, "Email or password is incorrect.");
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * A real BCrypt hash of a value nobody knows, compared against when the address is unknown so
     * that path costs the same as a wrong password.
     */
    private static final String DUMMY_HASH = "$2a$12$C6UzMDM.H6dfI/f/IKcEe.7Xr6vXKPFC1sYhFDlxbLQCyR8/QZHT2";

    /** Everything a caller needs to answer the request and set the cookies. */
    public record Session(
            User user,
            Business business,
            Role role,
            JwtService.IssuedAccessToken accessToken,
            String refreshToken) {}
}
