package dev.reception.business;

import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.error.FieldError;
import dev.reception.common.ids.IdGenerator;
import dev.reception.tenancy.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The questions the Receptionist is allowed to answer from.
 *
 * <p>Every FAQ is rendered verbatim into the system prompt, so the count ceiling is a cost control
 * as much as a usability one: fifty questions and answers at their maximum lengths is already a
 * substantial prompt to pay for on every conversation (docs/05-ai-architecture.md).
 */
@Service
public class FaqService {

    private final BusinessFaqRepository faqs;
    private final TenantContext tenant;
    private final IdGenerator ids;
    private final Clock clock;

    public FaqService(BusinessFaqRepository faqs, TenantContext tenant, IdGenerator ids, Clock clock) {
        this.faqs = faqs;
        this.tenant = tenant;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<BusinessFaq> list() {
        return faqs.findByBusinessIdOrderBySortOrderAscCreatedAtAsc(tenant.businessId());
    }

    /**
     * @param sortOrder where in the list it goes, or {@code null} to append. Appending is the
     *     overwhelmingly common case and the caller should not have to count the list to express it.
     */
    @Transactional
    public BusinessFaq create(String question, String answer, Integer sortOrder) {
        UUID businessId = tenant.businessId();
        long existing = faqs.countByBusinessId(businessId);
        if (existing >= BusinessFaq.MAX_PER_BUSINESS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "This business already has the maximum number of questions.",
                    List.of(new FieldError(
                            "question",
                            "You can have at most " + BusinessFaq.MAX_PER_BUSINESS
                                    + " questions. Remove one before adding another.")));
        }

        BusinessFaq faq = new BusinessFaq(
                ids.newId(),
                businessId,
                question.trim(),
                answer.trim(),
                sortOrder != null ? sortOrder : (int) existing,
                clock.instant());
        return faqs.save(faq);
    }

    /** Reordering is a patch of {@code sortOrder}; there is no separate reorder endpoint to keep in step. */
    @Transactional
    public BusinessFaq patch(UUID id, String question, String answer, Integer sortOrder) {
        BusinessFaq faq = faqs.findByBusinessIdAndId(tenant.businessId(), id)
                .orElseThrow(() -> ApiException.notFound("No such question."));
        faq.apply(trim(question), trim(answer), sortOrder, clock.instant());
        return faqs.save(faq);
    }

    @Transactional
    public void delete(UUID id) {
        if (faqs.deleteByBusinessIdAndId(tenant.businessId(), id) == 0) {
            throw ApiException.notFound("No such question.");
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
