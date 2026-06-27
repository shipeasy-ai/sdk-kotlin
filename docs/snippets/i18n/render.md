Label rendering is **client-side only** — the Kotlin server SDK has no `t()`.
Once the loader (from setup) has hydrated translations for `{{PROFILE}}`, the
browser SDK renders labels:

```js
// browser, @shipeasy/sdk client
import { t } from "@shipeasy/sdk/client";
t("checkout.pay_now");
```
