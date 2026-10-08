# General Coding Guidelines (Clean Code)

## Meaningful Names
- Use meaningful and searchable names.
- Avoid misleading names and meaningless prefixes/suffixes.
- Use pronounceable names.
- **Class names** should be Nouns; **Method names** should be Verbs.

## Functions
- Keep functions short (ideally under 20 lines).
- **Single Responsibility Principle:** Do one thing only.
- Maintain one level of abstraction per function.
- Limit arguments (max 3).
- Avoid side effects.

## Comments
- Code should explain itself; minimize comments.
- Do not use comments to compensate for bad code.
- **Good Comments:** Legal info, TODOs, Warnings, Public API docs.
- **Bad Comments:** Mumbling, redundancy, misleading info, commented-out code.

## Formatting
- **Vertical:** Keep related code close together.
- **Horizontal:** Keep line width reasonable (avoid horizontal scrolling).
- Use empty lines to separate distinct concepts.
- **No Emojis:** Strictly avoid using emojis in source code, comments, commit messages, or documentation.

## Objects & Data Structures
- **Objects:** Hide data, expose behavior.
- **Data Structures:** Expose data, no significant behavior.
- Follow the **Law of Demeter** (Principle of Least Knowledge).
- Avoid hybrids (half object, half data structure).

## Error Handling
- Use **Exceptions** instead of return codes.
- Write `Try-Catch-Finally` first.
- Provide context in error messages.
- **NEVER** return or pass `null`.
- Separate error handling from business logic.

## Boundaries
- Wrap third-party APIs/packages to minimize dependency.
- Use "Learning Tests" to explore external APIs.

## Unit Tests
- Treat test code with the same care as production code.
- Follow **FIRST**: Fast, Independent, Repeatable, Self-validating, Timely.
- One assert/concept per test.

## Classes
- Keep classes small and cohesive.
- Follow **SRP** (Single Responsibility) and **OCP** (Open-Closed Principle).

## Systems & Refactoring
- Separate construction (startup) from use (runtime).
- Use **Dependency Injection**.
- Follow the **Boy Scout Rule**: Always leave the code cleaner than you found it.
- Refactor in small steps with test coverage.