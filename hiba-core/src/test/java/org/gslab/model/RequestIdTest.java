package org.gslab.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RequestId} value object.
 * Tests validation, factory methods, and conversion functionality.
 */
public class RequestIdTest {

    // Valid UUID for testing
    private static final String VALID_UUID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String ANOTHER_VALID_UUID = "550e8400-e29b-41d4-a716-446655440000";

    // ========== Constructor and Validation Tests ==========

    @Test
    public void constructor_withValidUUID_shouldSucceed() {
        RequestId id = new RequestId(VALID_UUID);
        assertNotNull(id);
        assertEquals(VALID_UUID, id.value());
    }

    @Test
    void constructor_withNull_shouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> new RequestId(null));
    }

    @Test
    void constructor_withEmptyString_shouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> new RequestId(""));
    }

    @Test
    void constructor_withBlankString_shouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> new RequestId("   "));
    }

    @Test
    void constructor_withInvalidUUIDFormat_shouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> new RequestId("not-a-uuid"));
    }

    @Test
    void constructor_withInvalidUUIDFormat_numericOnly_shouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> new RequestId("12345"));
    }

    @Test
    void constructor_withInvalidUUIDFormat_wrongPattern_shouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> new RequestId("123e4567-e89b-12d3-a456")); // Too short
    }

    @Test
    public void constructor_withValidUUID_shouldAcceptVariousFormats() {
        // Test various valid UUID formats
        String[] validUUIDs = {
            "123e4567-e89b-12d3-a456-426614174000", // Standard format
            "550e8400-e29b-41d4-a716-446655440000", // Another valid UUID
            "00000000-0000-0000-0000-000000000000", // All zeros
            "ffffffff-ffff-ffff-ffff-ffffffffffff" // All f's
        };

        for (String uuid : validUUIDs) {
            RequestId id = new RequestId(uuid);
            assertEquals(uuid, id.value());
        }
    }

    // ========== Factory Method Tests ==========

    @Test
    public void generate_shouldCreateUniqueRequestId() {
        RequestId id1 = RequestId.generate();
        RequestId id2 = RequestId.generate();

        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1.value(), id2.value());

        // Verify they are valid UUIDs
        assertDoesNotThrow(() -> UUID.fromString(id1.value()));
        assertDoesNotThrow(() -> UUID.fromString(id2.value()));
    }

    @Test
    public void generate_shouldCreateValidUUID() {
        RequestId id = RequestId.generate();

        assertNotNull(id);
        assertNotNull(id.value());
        assertFalse(id.value().isBlank());

        // Should be parseable as UUID
        UUID uuid = UUID.fromString(id.value());
        assertNotNull(uuid);
    }

    @Test
    public void of_withValidUUID_shouldSucceed() {
        RequestId id = RequestId.of(VALID_UUID);

        assertNotNull(id);
        assertEquals(VALID_UUID, id.value());
    }

    @Test
    void of_withNullString_shouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> RequestId.of((String) null));
    }

    @Test
    void of_withBlank_shouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> RequestId.of("   "));
    }

    @Test
    void of_withInvalidUUID_shouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> RequestId.of("not-a-uuid"));
    }

    @Test
    public void of_withValidUUIDObject_shouldSucceed() {
        UUID originalUUID = UUID.randomUUID();
        RequestId id = RequestId.of(originalUUID);

        assertNotNull(id);
        assertEquals(originalUUID.toString(), id.value());
    }

    @Test
    void of_withNullUUID_shouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> RequestId.of((UUID) null));
    }

    @Test
    public void of_withUUIDObject_shouldPreserveValue() {
        UUID original = UUID.fromString(VALID_UUID);
        RequestId id = RequestId.of(original);

        assertEquals(VALID_UUID, id.value());
        assertEquals(original, id.toUUID());
    }

    // ========== Conversion Tests ==========

    @Test
    public void toUUID_shouldConvertCorrectly() {
        RequestId id = RequestId.of(VALID_UUID);
        UUID uuid = id.toUUID();

        assertNotNull(uuid);
        assertEquals(VALID_UUID, uuid.toString());
    }

    @Test
    public void toUUID_shouldBeIdempotent() {
        RequestId id = RequestId.of(VALID_UUID);
        UUID uuid1 = id.toUUID();
        UUID uuid2 = id.toUUID();

        assertEquals(uuid1, uuid2);
    }

    @Test
    public void roundTrip_UUIDToRequestIdToUUID_shouldPreserveValue() {
        UUID original = UUID.randomUUID();
        RequestId id = RequestId.of(original);
        UUID result = id.toUUID();

        assertEquals(original, result);
    }

    @Test
    public void roundTrip_StringToRequestIdToString_shouldPreserveValue() {
        String original = VALID_UUID;
        RequestId id = RequestId.of(original);
        String result = id.value();

        assertEquals(original, result);
    }

    // ========== toString() Tests ==========

    @Test
    public void toString_shouldReturnCleanUUIDString() {
        RequestId id = RequestId.of(VALID_UUID);
        String result = id.toString();

        assertEquals(VALID_UUID, result);
        // Should NOT contain "RequestId[value=...]"
        assertFalse(result.contains("RequestId"));
        assertFalse(result.contains("value="));
    }

    @Test
    public void toString_shouldBeConsistentWithValue() {
        RequestId id = RequestId.of(VALID_UUID);

        assertEquals(id.value(), id.toString());
    }

    // ========== Equality and HashCode Tests ==========

    @Test
    public void equals_withSameValue_shouldBeEqual() {
        RequestId id1 = RequestId.of(VALID_UUID);
        RequestId id2 = RequestId.of(VALID_UUID);

        assertEquals(id1, id2);
        assertEquals(id1.hashCode(), id2.hashCode());
    }

    @Test
    public void equals_withDifferentValue_shouldNotBeEqual() {
        RequestId id1 = RequestId.of(VALID_UUID);
        RequestId id2 = RequestId.of(ANOTHER_VALID_UUID);

        assertNotEquals(id1, id2);
    }

    @Test
    public void equals_withSameInstance_shouldBeEqual() {
        RequestId id = RequestId.of(VALID_UUID);

        assertEquals(id, id);
    }

    @Test
    public void equals_withNull_shouldNotBeEqual() {
        RequestId id = RequestId.of(VALID_UUID);

        assertNotEquals(id, null);
    }

    @Test
    public void hashCode_withSameValue_shouldBeConsistent() {
        RequestId id1 = RequestId.of(VALID_UUID);
        RequestId id2 = RequestId.of(VALID_UUID);

        assertEquals(id1.hashCode(), id2.hashCode());
    }

    // ========== Error Message Tests ==========

    @Test
    public void constructor_withNull_shouldHaveClearErrorMessage() {
        try {
            new RequestId(null);
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("RequestId cannot be null"));
        }
    }

    @Test
    public void constructor_withBlank_shouldHaveClearErrorMessage() {
        try {
            new RequestId("   ");
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("RequestId cannot be blank"));
        }
    }

    @Test
    public void constructor_withInvalidFormat_shouldHaveClearErrorMessage() {
        String invalidValue = "not-a-uuid";
        try {
            new RequestId(invalidValue);
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("RequestId must be a valid UUID format"));
            assertTrue(e.getMessage().contains(invalidValue));
        }
    }

    @Test
    public void of_withNullUUID_shouldHaveClearErrorMessage() {
        try {
            RequestId.of((UUID) null);
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("UUID cannot be null"));
        }
    }

    // ========== UNKNOWN Constant Tests ==========

    @Test
    public void unknown_shouldBeValidRequestId() {
        RequestId unknown = RequestId.UNKNOWN;

        assertNotNull(unknown);
        assertNotNull(unknown.value());
        assertEquals("00000000-0000-0000-0000-000000000000", unknown.value());
    }

    @Test
    public void unknown_shouldBeNilUUID() {
        RequestId unknown = RequestId.UNKNOWN;

        // Nil UUID as defined in RFC 4122
        UUID nilUUID = new UUID(0L, 0L);
        assertEquals(nilUUID.toString(), unknown.value());
    }

    @Test
    public void unknown_shouldConvertToUUID() {
        RequestId unknown = RequestId.UNKNOWN;

        // Should not throw exception
        assertDoesNotThrow(() -> unknown.toUUID());

        UUID uuid = unknown.toUUID();
        assertNotNull(uuid);
        assertEquals("00000000-0000-0000-0000-000000000000", uuid.toString());
    }

    @Test
    public void unknown_shouldBeSingleton() {
        RequestId unknown1 = RequestId.UNKNOWN;
        RequestId unknown2 = RequestId.UNKNOWN;

        // Should be the same instance (singleton constant)
        assertSame(unknown1, unknown2);
    }

    @Test
    public void unknown_toString_shouldReturnCleanNilUUID() {
        RequestId unknown = RequestId.UNKNOWN;
        String result = unknown.toString();

        assertEquals("00000000-0000-0000-0000-000000000000", result);
        // Should NOT contain "RequestId[value=...]"
        assertFalse(result.contains("RequestId"));
        assertFalse(result.contains("value="));
    }

    @Test
    public void unknown_shouldEqualAnotherNilUUID() {
        RequestId unknown = RequestId.UNKNOWN;
        RequestId anotherNil = RequestId.of("00000000-0000-0000-0000-000000000000");

        // Should be equal by value (but not same instance since of() creates new instance)
        assertEquals(unknown, anotherNil);
        assertEquals(unknown.hashCode(), anotherNil.hashCode());
        assertNotSame(unknown, anotherNil); // Different instances, but equal values
    }

    @Test
    public void unknown_shouldNotEqualGeneratedId() {
        RequestId unknown = RequestId.UNKNOWN;
        RequestId generated = RequestId.generate();

        // Should not be equal
        assertNotEquals(unknown, generated);
        assertNotEquals(unknown.value(), generated.value());
    }

    @Test
    public void unknown_shouldNotEqualOtherValidId() {
        RequestId unknown = RequestId.UNKNOWN;
        RequestId other = RequestId.of(VALID_UUID);

        assertNotEquals(unknown, other);
    }

    // ========== Helper Method for Java 8 Compatibility ==========

    /**
     * Helper method to assert that a lambda does not throw an exception.
     * (JUnit 4 doesn't have assertDoesNotThrow, so we implement it here)
     */
    private void assertDoesNotThrow(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            fail("Expected no exception, but got: " + e.getClass().getName() + " - " + e.getMessage());
        }
    }
}
