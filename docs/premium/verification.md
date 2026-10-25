# تحویل و شواهد بررسی Premium — ۲۰۲۶-۱۰-۲۱

## محدوده و محل واقعی

- پیاده‌سازی: `/Users/busnet/Downloads/SPD-VPN-Backend-v0.1.0`، همان مخزن مستقل Ktor روی branch `main`.
- سورس Android در `/Users/busnet/Documents/SpdVpn` فقط برای قراردادهای Account/Premium/Xray خوانده شد.
  diff باینری و status قبل/بعد با `cmp` برابر بود؛ dirty worktree قبلی حفظ شد.
- `/Users/busnet/Downloads/SpdVpn-panel-development` مرجع خواندنی بود. مدل‌های محتوا، store و
  presence موجود به‌صورت انتخابی به backend واقعی اضافه شدند؛ احراز هویت env قدیمی وارد نشد.
- هیچ AGENTS.md در سه پروژه یا والدهای بررسی‌شده یافت نشد. دادهٔ production، env واقعی، SSH،
  Caddy، firewall و کد Android/OpenVPN/SoftEther/Xray تغییر نکردند. commit، push و deployment انجام نشد.
- snapshot وضعیت پیش از کار: `/private/tmp/spd-premium-20261021-K4mEqd/backend-source-before.tar` و فایل‌های before در همان پوشه.

## تغییرات اصلی

| بخش | فایل‌ها |
| --- | --- |
| اتصال به Ktor و حفظ APIهای Free | `src/main/kotlin/app/spdvpn/backend/Application.kt` |
| جلوگیری از refresh هم‌زمان تکراری | `src/main/kotlin/app/spdvpn/backend/service/CatalogService.kt`؛ همان pipeline و cache |
| migration و storage | `src/main/resources/db/migrations/001_premium.sql`، `premium/PremiumDatabase.kt` |
| قرارداد و تنظیمات | `premium/PremiumModels.kt`، `PremiumConfig.kt` |
| امنیت و bootstrap | `premium/PasswordHasher.kt`، `PremiumSecurity.kt`، `BootstrapAdmin.kt` |
| عملیات durable و adapter | `premium/PremiumService.kt`، `PasarGuard.kt`، `PremiumRuntime.kt` |
| routeها | `premium/PremiumRoutes.kt`؛ مرورگر مدیر و Bearer کاربران جدا |
| سازگاری محتوای پنل/آمار | `panel/PanelModels.kt`، `PanelContentStore.kt`، `PanelService.kt`، `ClientPresenceTracker.kt` |
| رابط فارسی RTL | `src/main/resources/admin/index.html`، `app.js`، `styles.css` |
| تست‌های جدید | `src/test/kotlin/app/spdvpn/backend/premium/{PremiumServiceTest,PremiumRoutesTest,PasarGuardAdapterTest,ManualCatalogRefreshTest}.kt` |
| بررسی مرورگر | `scripts/admin-browser-test.mjs`، `package.json`، `package-lock.json` |
| نمونهٔ تنظیم و ارائه | `.env.example`، `compose.yaml`، `.gitignore`، `README.md`، `build.gradle.kts` |
| راهنما و قرارداد | همین پوشه: `setup.md`، `android-api.md`، `pasarguard-v5.4.1.md`، `screenshots/` |

همهٔ نام‌های کوتاه `premium/...` و `panel/...` در جدول، نسبت به
`src/main/kotlin/app/spdvpn/backend/` هستند. ZIP از قبل موجود `SpdVpn-panel-input.zip` تغییر نکرد و وارد کار نشد.
Node_modules، DB، فایل رمز آزمایشی و خروجی‌های build در تغییرات source قرار نگرفته‌اند.

## فرمان‌ها و نتیجهٔ واقعی

```sh
./gradlew test :prober:test build buildFatJar --no-daemon --console=plain
./gradlew :prober:test --rerun-tasks --no-daemon --console=plain
npm ci --ignore-scripts
npm run check
# با backend واقعی loopback، DB موقت، مدیر bootstrap‌شده و Chrome موجود:
npm run test:browser
git diff --check
```

نصب dependency در این اجرا با `npm install --ignore-scripts` انجام شد و lockfile تولید شد؛
`npm ci` فرمان بازتولید از lockfile است و به‌عنوان اجرای جداگانه ادعا نمی‌شود.

| بررسی | نتیجه |
| --- | --- |
| تست‌های root backend | **67 passed**، 13 suite، صفر fail/error/skip |
| تست‌های prober | **35 passed**، 6 suite، صفر fail/error/skip |
| مجموع تست JVM | **102 passed** |
| Gradle build و fat JAR | **PASS** |
| JavaScript syntax check | **PASS** |
| Playwright با Chrome موجود | **PASS**؛ backend واقعی و DB موقت، بدون route mock |
| ورود نامعتبر/ورود امن، Origin و CSRF | **PASS** در تست route و مرورگر |
| session revoke/expiry، rotation و نقش جدا | **PASS** |
| مالکیت account/profile و subscription gate | **PASS** |
| تمدید فعال/منقضی، دائمی/حجم، disabled | **PASS** |
| خطا پس از create/PUT، retry و restart | **PASS**؛ اثر تکراری ندارد |
| drift بیرونی پیش از تمدید و تطبیق صریح | **PASS** |
| LINK یکتا و اصلاح اتصال تأییدنشده | **PASS** |
| حفظ API Free/config و cache در خطا | **PASS** |
| HTTP adapter در برابر پاسخ کنترل‌شده | **PASS**؛ route/schema نسخهٔ pinned |
| پاک‌بودن diff از whitespace error | **PASS** |
| بررسی compose با env نمونه و `config --quiet` | **PASS**؛ بدون چاپ مقادیر secret |
| تغییرات source/status Android | **بدون تغییر توسط این تسک** |
| ساخت و تمدید روی PasarGuard واقعی | **NOT_RUN**؛ نمونهٔ آزمایشی مجاز/credential ارائه نشده |
| آزمایش روی گوشی Android / تونل واقعی Premium | **NOT_RUN**؛ این مرحله Android را تغییر نمی‌دهد |
| Docker image build / deployment | **NOT_RUN**؛ Gradle artifact ساخته شد، روی production کاری انجام نشد |

هش JAR نهایی `build/libs/spd-vpn-backend-all.jar` در این تحویل:

```text
SHA-256 f146a99a0819744a986642c342d414f92e8cb1ba2bc693faf7799a39d4556997
```

لاگ‌های بررسی در `/private/tmp/spd-premium-20261021-K4mEqd/verify.log`، `prober-fresh.log` و `browser-final-v3.log` هستند.
warningهای compile مربوط به API قدیمی intercept در Ktor و null assertionهای کد محتوای مرجع هستند؛
build و تست موفق‌اند. تست API آزمایشی از fakeهای صریح فقط در `src/test` استفاده می‌کند؛ runtime
تنظیمات mock یا جایگزین خودکار PasarGuard ندارد.

## تصاویر بررسی‌شده

تصاویر از همین رابط و backend واقعی روی `127.0.0.1:18765` با دیتابیس **موقت** گرفته شدند.
سرور موقت پس از پایان تست به‌صورت عادی متوقف شد؛ سرویس دیگری متوقف یا جایگزین نشد.
حساب‌های نمایش‌داده‌شده fixtureهای دستیِ تست مرورگر در DB جدا هستند؛ دادهٔ نمایشی در runtime نیستند.
به‌علت خالی‌بودن تنظیم PasarGuard در تست، نتیجهٔ واقعی ساخت حساب `FAILED / PASARGUARD_NOT_CONFIGURED`
بود و UI آن را موفقیت VPN نمایش نداد. آمار صفر حضور از tracker واقعیِ بدون کلاینت گزارش‌دهنده است.
کاتالوگ Free از منبع عمومی واقعی دریافت شد و cache فقط در مسیر موقت بود.

- [ورود](screenshots/01-login.png)
- [داشبورد](screenshots/02-dashboard.png)
- [کاربران و ساخت حساب](screenshots/03-users.png)
- [جزئیات و خطای همگام‌سازی](screenshots/04-user-detail.png)
- [پلن‌ها](screenshots/05-plans.png)
- [سرورهای رایگان](screenshots/06-free.png)
- [تاریخچه](screenshots/07-audit.png)
- [نسخهٔ موبایل](screenshots/08-mobile.png)

تصاویر ورود، داشبورد، جزئیات و موبایل بصری بررسی شدند؛ تست مرورگر هم نبود overflow افقی موبایل
و نبود خطای JavaScript را تأیید کرد. فرم‌ها متن دریافتی را با textContent نمایش می‌دهند و HTML اجرا نمی‌کنند.
در تست مرورگر رمزها در screenshot نیستند؛ ورودی رمز پس از درخواست پاک می‌شود.

## محدودیت‌های تحویل

- نسخهٔ pinned PasarGuard باید در نمونهٔ مقصد، گروه‌های معتبر، مجوز مدیر اختصاصی و template
  سازگار native TUN داشته باشد. تا تست واقعی این موارد، ادعای آماده‌بودن اتصال production نداریم.
- کشور در subscription upstream قرارداد جدا ندارد و نامشخص نمایش داده می‌شود. هیچ سرور/کشور جعلی اضافه نشده.
- SQLite و worker برای یک instance هستند. HA، دیتابیس چندنویسنده، node/inbound management، پرداخت،
  نمایندگی، تخفیف و حسابداری پیاده نشده‌اند. سقف/مصرف واقعی به PasarGuard واگذار می‌شود.
- عملیات ناموفق باید پیش از تغییر اشتراک بعدی تعیین تکلیف شود؛ فرم رمز مستقل است. accountهای
  synced قبل از تغییر، خواندن تازهٔ upstream می‌خواهند؛ هنگام outage درخواست تازهٔ تغییر پذیرفته نمی‌شود.
- retry مطلق است؛ quota renewal مصرف را reset نمی‌کند. مقدار درخواستی و تأییدشده جدا هستند؛
  مدیر پیش از تطبیق باید اثر بازنویسی مقدار SPD را تأیید کند.
- stats حضور self-reported است و برای billing/هویت معتبر استفاده نمی‌شود.
- enforcement روی VPN فعال Android، محدودیت دستگاه و اعتبارسنجی دوره‌ای داخل VpnService نیاز به مرحلهٔ Android دارند.
- این تحویل credential سرورهای عمومی VPN Gate را به حساب SPD تبدیل نمی‌کند و دسترسی سایر کلاینت‌ها
  به VPN Gate را لغو نمی‌کند. برای Premium، credential و لغو اجرایی متعلق به PasarGuard است.

راهنمای اجرای محلی و bootstrap در [setup.md](setup.md)، قرارداد اپ در [android-api.md](android-api.md)
و قرارداد/منطق بازیابی adapter در [pasarguard-v5.4.1.md](pasarguard-v5.4.1.md) آمده‌اند.
