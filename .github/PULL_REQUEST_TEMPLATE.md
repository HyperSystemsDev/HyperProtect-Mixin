## Description

<!-- Provide a brief description of the changes in this PR -->

## Type of Change

<!-- Mark the relevant option(s) with an "x" -->

- [ ] Bug fix (non-breaking change which fixes an issue)
- [ ] New feature (non-breaking change which adds functionality)
- [ ] New hook/interceptor
- [ ] Breaking change (fix or feature that would cause existing functionality to not work as expected)
- [ ] Documentation update
- [ ] Refactoring (no functional changes)

## Related Issues

<!-- Link any related issues here using "Fixes #123" or "Relates to #123" -->

## Testing

<!-- Describe how you tested your changes -->

- [ ] Tested on local development server with `--accept-early-plugins`
- [ ] Verified mixin injection succeeds (check system properties)
- [ ] Tested with a consumer mod (e.g., HyperFactions)
- [ ] Verified fail-open behavior (hook errors don't crash the server)

## Mixin-Specific Checklist

- [ ] New interceptors follow the standard verdict protocol (0/1/2/3)
- [ ] New interceptors set their system property on load (`hyperprotect.intercept.*`)
- [ ] New interceptors use `FaultReporter` for exception handling
- [ ] New interceptors use volatile `HookSlot` caching pattern
- [ ] Bridge slot constants updated in `ProtectionBridge.java` (if new slot)
- [ ] Bridge compatibility maintained (no breaking changes to existing slots)
- [ ] Fail-open behavior preserved (errors allow actions, not block them)

## General Checklist

- [ ] My code follows the project's coding style
- [ ] I have performed a self-review of my own code
- [ ] I have updated the documentation (if applicable)
- [ ] My changes generate no new warnings or errors
