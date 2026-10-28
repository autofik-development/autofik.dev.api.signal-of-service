package com.autofik.sos.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.ResponseEntity;

/** The handler branches the main mapping test does not reach. */
class GlobalExceptionHandlerBranchesTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  private static ConstraintViolation<Object> violation(String path, String message) {
    @SuppressWarnings("unchecked")
    ConstraintViolation<Object> v = mock(ConstraintViolation.class);
    Path p = mock(Path.class);
    when(p.toString()).thenReturn(path);
    when(v.getPropertyPath()).thenReturn(p);
    when(v.getMessage()).thenReturn(message);
    return v;
  }

  @Test
  void aBadSortProperty_isA400_whetherBareOrWrapped() {
    PropertyReferenceException bad = mock(PropertyReferenceException.class);

    ResponseEntity<ErrorResponse> bare = handler.handleBadSort(bad);
    ResponseEntity<ErrorResponse> wrapped =
        handler.handleBadSort(new InvalidDataAccessApiUsageException("x", bad));

    for (ResponseEntity<ErrorResponse> r : java.util.List.of(bare, wrapped)) {
      assertThat(r.getStatusCode().value()).isEqualTo(400);
      assertThat(r.getBody().message()).isEqualTo("Unknown sort property");
    }
  }

  @Test
  void anInvalidDataAccessUsage_thatIsNotASort_isA500_withoutItsText() {
    ResponseEntity<ErrorResponse> r =
        handler.handleBadSort(new InvalidDataAccessApiUsageException("secret table name"));

    assertThat(r.getStatusCode().value()).isEqualTo(500);
    assertThat(r.getBody().message()).doesNotContain("secret");
  }

  @Test
  void aConstraintViolation_keepsTheLastSegmentOfThePath_andOneMessagePerParameter() {
    Set<ConstraintViolation<?>> violations = new LinkedHashSet<>();
    violations.add(violation("updateLocation.lng", "must be at most 107"));
    violations.add(violation("other.lng", "second message for the same parameter"));
    violations.add(violation("plain", "no dot in this path"));

    ErrorResponse r =
        handler.handleConstraintViolation(new ConstraintViolationException(violations));

    // Which of the two "lng" messages wins depends on the set's order; exactly one does.
    assertThat(r.details()).containsEntry("plain", "no dot in this path").hasSize(2);
    assertThat(r.details().get("lng"))
        .isIn("must be at most 107", "second message for the same parameter");
  }
}
