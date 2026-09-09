package dev.reception.customers;

import dev.reception.business.BusinessService;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.FieldError;
import dev.reception.common.ids.IdGenerator;
import dev.reception.common.phone.PhoneField;
import dev.reception.tenancy.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer identity, keyed on the phone number.
 *
 * <p>Everything here exists to answer one question well: <em>is this the same person who booked
 * before?</em> The answer is the normalised phone number and nothing else — not the name, which
 * people give differently every time, and not the email, which many bookings do not have.
 */
@Service
public class CustomerService {

    /** Long enough that a two-letter typo does not return the whole book. */
    private static final int MAX_PAGE_SIZE = 100;

    private final CustomerRepository customers;
    private final BusinessService businesses;
    private final TenantContext tenant;
    private final IdGenerator ids;
    private final Clock clock;

    public CustomerService(
            CustomerRepository customers,
            BusinessService businesses,
            TenantContext tenant,
            IdGenerator ids,
            Clock clock) {
        this.customers = customers;
        this.businesses = businesses;
        this.tenant = tenant;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * The Customer for this phone number, creating one if this Business has not seen it.
     *
     * <p><strong>A differing name does not overwrite the stored one.</strong> The common reason a
     * known number arrives with a new name is somebody booking for a partner, a child or a
     * colleague — and renaming the customer would destroy the record of who they are, silently, on
     * every such booking (docs/03-data-model.md §2). The Appointment records the name it was given;
     * only an explicit correction through {@link #patch} changes the Customer.
     *
     * <p>Called inside the booking transaction, so a booking that fails afterwards leaves no
     * customer behind.
     */
    @Transactional
    public Customer findOrCreate(String rawPhone, String fullName, String email, CustomerFieldNames fields) {
        UUID businessId = tenant.businessId();
        String phone = requiredPhone(rawPhone, fields);

        return customers
                .findByBusinessIdAndPhone(businessId, phone)
                .orElseGet(() -> customers.save(new Customer(
                        ids.newId(),
                        businessId,
                        requiredName(fullName, fields),
                        phone,
                        blankToNull(email),
                        clock.instant())));
    }

    /**
     * The Customers behind a page of Appointments, in one query.
     *
     * <p>Keyed by id for the caller to join against. One call per page rather than one per row: an
     * appointment list of fifty is fifty customer reads otherwise, and it is the screen an owner
     * leaves open all day.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Customer> byIds(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return customers.findByBusinessIdAndIdIn(tenant.businessId(), ids).stream()
                .collect(Collectors.toMap(Customer::getId, customer -> customer));
    }

    @Transactional(readOnly = true)
    public Customer read(UUID id) {
        return customers.findByBusinessIdAndId(tenant.businessId(), id)
                .orElseThrow(() -> ApiException.notFound("No such customer."));
    }

    /**
     * @param query {@code null} or blank lists everyone, which is what an empty search box means
     */
    @Transactional(readOnly = true)
    public Page<Customer> search(String query, int page, int size) {
        UUID businessId = tenant.businessId();
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));

        if (query == null || query.isBlank()) {
            return customers.findByBusinessIdOrderByFullNameAsc(businessId, pageable);
        }
        // Lowercased here rather than in the query, so the parameter matches the lower(full_name)
        // expression index instead of forcing a per-row function call on the column.
        String pattern = "%" + query.trim().toLowerCase() + "%";
        return customers.findByBusinessIdAndMatching(businessId, pattern, pageable);
    }

    /**
     * Corrects a name or an email. The phone number is not correctable: it is half the identity key,
     * and changing it would either collide with another Customer or silently move one person's
     * history onto another number. The supported fix is to book under the right number, which
     * creates the right Customer.
     *
     * <p>Absent leaves, blank clears, a value sets — see {@link Customer#applyCorrection}.
     */
    @Transactional
    public Customer patch(UUID id, String fullName, String email, CustomerFieldNames fields) {
        Customer customer = read(id);
        if (fullName != null && fullName.isBlank()) {
            throw invalid(fields.fullName(), "Enter a name.");
        }
        customer.applyCorrection(trim(fullName), trim(email), clock.instant());
        return customers.save(customer);
    }

    /**
     * A phone number is mandatory for a Customer, so blank is a validation failure here rather than
     * the {@code null} {@link PhoneField} returns for an optional field.
     *
     * <p>Reported under the caller's name for it, never under this package's. See
     * {@link CustomerFieldNames} for the defect that taught us the difference.
     */
    private String requiredPhone(String raw, CustomerFieldNames fields) {
        String country = businesses.read().country();
        if (PhoneField.isAbsent(raw)) {
            throw invalid(fields.phone(), "Enter a phone number.");
        }
        return PhoneField.parse(raw, country)
                .orElseThrow(() -> invalid(fields.phone(), PhoneField.unreadableMessage(country, fields.audience())));
    }

    private static String requiredName(String fullName, CustomerFieldNames fields) {
        if (fullName == null || fullName.isBlank()) {
            throw invalid(fields.fullName(), "Enter a name.");
        }
        return fullName.trim();
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED,
                "One or more fields are invalid.",
                List.of(new FieldError(field, message)));
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
