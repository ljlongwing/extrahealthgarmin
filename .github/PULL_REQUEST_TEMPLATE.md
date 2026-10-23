## Summary

<!-- What changed and why. One or two sentences. -->

## Related issue

<!-- GitHub issue or beads task (e.g. tefal2garmin-123). "None" is fine for small fixes. -->

## Checklist

- [ ] Unit test included if this touches sync logic, aggregation, FIT encoding or auth (the suite mirrors the packages under `app/src/test/java/com/zamotkin/extrahealthgarmin/`)
- [ ] New/changed UI strings added to both `res/values/strings.xml` and `res/values-ru/strings.xml`
- [ ] `./gradlew testDebugUnitTest` and `./gradlew lintDebug` pass
- [ ] No secrets, tokens, credentials or keystores in code, logs or screenshots
