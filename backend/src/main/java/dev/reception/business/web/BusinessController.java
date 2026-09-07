package dev.reception.business.web;

import dev.reception.business.Business;
import dev.reception.business.BusinessHoursService;
import dev.reception.business.BusinessPatch;
import dev.reception.business.BusinessService;
import dev.reception.business.ClosureService;
import dev.reception.business.FaqService;
import dev.reception.business.OnboardingService;
import jakarta.validation.Valid;
import java.time.DayOfWeek;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The business configuration surface (docs/04-api-overview.md §5).
 *
 * <p>The controller maps between the wire and the application services and contains no {@code if}
 * about a business rule — the layering rule this codebase enforces rather than suggests
 * (docs/02-product-architecture.md §2).
 *
 * <p><strong>No method here names a business.</strong> Every one of them resolves the tenant through
 * {@code TenantContext} inside the service, so a caller cannot address someone else's configuration
 * — there is no parameter in which to try. The {@code {id}} path variables are for a closure or a
 * FAQ, and each is looked up by {@code (businessId, id)}, which is why another tenant's id comes
 * back as {@code 404} rather than as their data.
 *
 * <p>Configuration is an owner's job: {@code OWNER} and {@code ADMIN} only, declared once for the
 * class rather than repeated per method (docs/06-security.md §3). {@code STAFF} has no login in the
 * MVP, so this is the rule arriving before the role that would test it.
 */
@RestController
@RequestMapping("/business")
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
public class BusinessController {

    private final BusinessService businesses;
    private final BusinessHoursService hours;
    private final ClosureService closures;
    private final FaqService faqs;
    private final OnboardingService onboarding;

    public BusinessController(
            BusinessService businesses,
            BusinessHoursService hours,
            ClosureService closures,
            FaqService faqs,
            OnboardingService onboarding) {
        this.businesses = businesses;
        this.hours = hours;
        this.closures = closures;
        this.faqs = faqs;
        this.onboarding = onboarding;
    }

    @GetMapping
    public BusinessResponses.BusinessProfile read() {
        return BusinessResponses.BusinessProfile.of(businesses.read());
    }

    @PatchMapping
    public BusinessResponses.BusinessProfile patch(@Valid @RequestBody BusinessRequests.PatchBusiness request) {
        BusinessPatch patch = new BusinessPatch(
                request.name(),
                request.slug(),
                request.timezone(),
                request.currency(),
                request.description(),
                request.addressLine(),
                request.city(),
                request.country(),
                request.phone(),
                request.email(),
                request.website(),
                request.slotIntervalMinutes(),
                request.minLeadTimeMinutes(),
                request.maxAdvanceDays(),
                request.cancellationWindowHours(),
                request.cancellationPolicy(),
                request.aiEnabled(),
                request.aiAdditionalInfo(),
                request.aiDailyCostCapCents());
        return BusinessResponses.BusinessProfile.of(businesses.patch(patch));
    }

    // -----------------------------------------------------------------------
    // Hours
    // -----------------------------------------------------------------------

    @GetMapping("/hours")
    public BusinessResponses.WeekHours readHours() {
        return BusinessResponses.WeekHours.of(timezone(), hours.read());
    }

    /** Replaces the whole week. A day absent from the payload is closed. */
    @PutMapping("/hours")
    public BusinessResponses.WeekHours replaceHours(@Valid @RequestBody BusinessRequests.ReplaceHours request) {
        List<BusinessHoursService.Interval> week = request.hours().stream()
                .map(interval -> new BusinessHoursService.Interval(
                        DayOfWeek.of(interval.dayOfWeek()), interval.opensAt(), interval.closesAt()))
                .toList();
        return BusinessResponses.WeekHours.of(timezone(), hours.replaceWeek(week));
    }

    // -----------------------------------------------------------------------
    // Closures
    // -----------------------------------------------------------------------

    @GetMapping("/closures")
    public BusinessResponses.ClosureList readClosures() {
        Business business = businesses.read();
        return new BusinessResponses.ClosureList(
                business.timezone().getId(),
                closures.list().stream()
                        .map(closure -> BusinessResponses.Closure.of(closure, business.timezone()))
                        .toList());
    }

    @PostMapping("/closures")
    @ResponseStatus(HttpStatus.CREATED)
    public BusinessResponses.CreatedClosure createClosure(
            @Valid @RequestBody BusinessRequests.CreateClosure request) {
        ClosureService.CreatedClosure created =
                closures.create(request.startDate(), request.endDate(), request.reason());
        return BusinessResponses.CreatedClosure.of(created, businesses.read().timezone());
    }

    @DeleteMapping("/closures/{id}")
    public ResponseEntity<Void> deleteClosure(@PathVariable UUID id) {
        closures.delete(id);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // FAQs
    // -----------------------------------------------------------------------

    @GetMapping("/faqs")
    public BusinessResponses.FaqList readFaqs() {
        return BusinessResponses.FaqList.of(faqs.list());
    }

    @PostMapping("/faqs")
    @ResponseStatus(HttpStatus.CREATED)
    public BusinessResponses.Faq createFaq(@Valid @RequestBody BusinessRequests.CreateFaq request) {
        return BusinessResponses.Faq.of(faqs.create(request.question(), request.answer(), request.sortOrder()));
    }

    @PatchMapping("/faqs/{id}")
    public BusinessResponses.Faq patchFaq(
            @PathVariable UUID id, @Valid @RequestBody BusinessRequests.PatchFaq request) {
        return BusinessResponses.Faq.of(
                faqs.patch(id, request.question(), request.answer(), request.sortOrder()));
    }

    @DeleteMapping("/faqs/{id}")
    public ResponseEntity<Void> deleteFaq(@PathVariable UUID id) {
        faqs.delete(id);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Onboarding
    // -----------------------------------------------------------------------

    /** Derived state, never stored, so the dashboard cannot show a checklist reality has moved past. */
    @GetMapping("/onboarding")
    public BusinessResponses.Onboarding onboarding() {
        return BusinessResponses.Onboarding.of(onboarding.checklist());
    }

    private String timezone() {
        return businesses.read().timezone().getId();
    }
}
