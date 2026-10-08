package org.gslab.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Value object representing a unique request identifier in the HiBA distributed system.
 * <p>
 * RequestId is an immutable wrapper around a UUID string that ensures:
 * <ul>
 *   <li>Non-null, non-blank values</li>
 *   <li>Valid UUID format (RFC 4122)</li>
 *   <li>Type-safe request tracking across distributed components</li>
 * </ul>
 * <p>
 * Use {@link #generate()} to create new request IDs or {@link #of(String)}, {@link #of(UUID)}
 * to reconstruct from stored/transmitted values.
 *
 * @since 1.0
 */
public record RequestId(String value) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The Nil UUID as defined in RFC 4122 Section 4.1.7.
     * <p>
     * This special RequestId constant represents an unknown, invalid, or unidentifiable request.
     * It uses the nil UUID (all zeros) which is the standard way to represent "no UUID" in RFC 4122.
     * <p>
     * <b>When to use:</b>
     * <ul>
     *   <li>Exception handlers where no valid requestId is available</li>
     *   <li>Error responses before request parsing completes</li>
     *   <li>Fallback scenarios when requestId cannot be determined</li>
     * </ul>
     * <p>
     * <b>Example usage:</b>
     * <pre>
     * &#64;Override
     * public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
     *     // No valid requestId available in exception handler
     *     sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
     *                      "Internal error", RequestId.UNKNOWN);
     * }
     * </pre>
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc4122#section-4.1.7">RFC 4122 Section 4.1.7 - Nil UUID</a>
     * @since 1.0
     */
    public static final RequestId UNKNOWN = new RequestId("00000000-0000-0000-0000-000000000000");

    /**
     * Compact constructor with validation. Ensures the value is non-null, non-blank, and a valid UUID format.
     *
     * @throws IllegalArgumentException if value is null, blank, or not a valid UUID
     */
    public RequestId {
        if (value == null) {
            throw new IllegalArgumentException("RequestId cannot be null");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("RequestId cannot be blank");
        }
        if (!isValid(value)) {
            throw new IllegalArgumentException("RequestId must be a valid UUID format, but got: " + value);
        }
    }

    /**
     * Generates a new random RequestId using UUID version 4.
     *
     * @return a new unique RequestId
     */
    public static RequestId generate() {
        return new RequestId(UUID.randomUUID().toString());
    }

    /**
     * Creates a RequestId from an existing string value. Typically used when deserializing or reconstructing from
     * storage.
     *
     * @param value the UUID string (must be valid UUID format)
     * @return a RequestId wrapping the value
     * @throws IllegalArgumentException if value is null, blank, or invalid UUID
     */
    public static RequestId of(String value) {
        return new RequestId(value);
    }

    /**
     * Creates a RequestId from a UUID object.
     *
     * @param uuid the UUID
     * @return a RequestId wrapping the UUID's string representation
     * @throws IllegalArgumentException if uuid is null
     */
    public static RequestId of(UUID uuid) {
        if (uuid == null) {
            throw new IllegalArgumentException("UUID cannot be null");
        }
        return new RequestId(uuid.toString());
    }

    /**
     * Converts this RequestId to a UUID object.
     *
     * @return the UUID representation
     * @throws IllegalArgumentException if the value is not a valid UUID (should never happen due to validation)
     */
    public UUID toUUID() {
        return UUID.fromString(value);
    }

    /**
     * Returns the UUID string directly for cleaner logging output.
     * <p>
     * Example: Instead of "RequestId[value=123e4567-...]", this returns "123e4567-..."
     *
     * @return the UUID string
     */
    @NotNull
    @Override
    public String toString() {
        return value;
    }

    /**
     * Checks if this RequestId is the special UNKNOWN constant.
     *
     * @return true if this is UNKNOWN, false otherwise
     */
    public boolean isUnknown() {
        return this.equals(UNKNOWN);
    }

    /**
     * Validates whether a string is in valid UUID format.
     *
     * @param value the string to validate
     * @return true if valid UUID format, false otherwise
     */
    private static boolean isValid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
