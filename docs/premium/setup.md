# راه‌اندازی حساب‌های SPD و پنل Premium

این تغییر داخل بک‌اند واقعی `/Users/busnet/Downloads/SPD-VPN-Backend-v0.1.0` انجام شده است.
Android فقط برای استخراج قرارداد خوانده شده؛ تغییری نکرده است. API رایگان کاتالوگ،
health، config و prober حفظ شده‌اند. هیچ استقراری روی production انجام نشده است.

## معماری

- همین برنامهٔ Ktor: پنل `/admin`، حساب ورود SPD، نشست‌های مدیر/کاربر و adapter سرویس VPN.
- همان ساختار رابط سبک موجود در پوشهٔ توسعهٔ پنل: HTML/CSS/JavaScript فارسی و RTL،
  به‌صورت resource داخل JAR. سرویس وب یا احراز هویت موازی ساخته نشده؛ runtime نیازی به Node ندارد.
- SQLite مستقل `data/premium.sqlite`: حساب‌ها، hash رمزها، پلن‌ها، نشست‌های hash‌شده،
  صف عملیات و audit. migration نسخهٔ ۱ در `src/main/resources/db/migrations/001_premium.sql`
  هنگام شروع به‌صورت تراکنشی اعمال می‌شود؛ schema آیندهٔ ناشناخته رد می‌شود.
- کاتالوگ و health همچنان snapshotهای مستقل خود را دارند. مسیرهای آن‌ها، DB و فایل محتوای پنل باید متفاوت باشند.
- محتوای نسخه‌ها/پیام‌ها/پیشنهادها از مدل و store موجود پنل توسعه استفاده می‌کند و در
  `panel-content.json` می‌ماند. ورود مدیر قدیمی مبتنی بر رمز env جایگزین نشده/فعال نشده؛
  تمام routeهای مدیریتی جدید از حساب مدیر bootstrap‌شده در SQLite استفاده می‌کنند.
- PasarGuard مالک دسترسی اجرایی VPN، پروفایل و مصرف/سقف/انقضاست. SPD node یا inbound مدیریت نمی‌کند.

## ساخت و اجرای محلی

JDK 17 یا بالاتر با toolchain 17، Gradle wrapper موجود:

```sh
./gradlew test :prober:test build buildFatJar
npm ci --ignore-scripts
npm run check
```

پنل build جداگانه‌ای برای runtime ندارد؛ فایل‌ها همراه `processResources` در JAR قرار می‌گیرند.
Node/Playwright فقط برای بررسی رابط است.

ابتدا مدیر اولیه را **از ترمینال سرور و پیش از بازکردن دسترسی عمومی** بسازید:

```sh
SPD_DATABASE_PATH=/absolute/private/data/premium.sqlite \
  java -cp build/libs/spd-vpn-backend-all.jar \
  app.spdvpn.backend.premium.BootstrapAdminKt YOUR_ADMIN_USERNAME
```

رمز در console بدون echo خوانده می‌شود؛ حداقل ۱۲ و حداکثر ۲۵۶ نویسه. آرگومان رمز،
رمز پیش‌فرض یا endpoint عمومی ساخت مدیر وجود ندارد. اجرای دوباره پس از ساخت اولین مدیر رد می‌شود.
برای automation کنترل‌شده `--password-stdin` و برای Gradle
`./gradlew bootstrapAdmin -PadminUsername=YOUR_ADMIN_USERNAME` با stdin مخفی قابل استفاده است؛
رمز را داخل command line، history یا فایل پروژه نگذارید. CLI رمز را plaintext ذخیره نمی‌کند.
در این نسخه UI ساخت مدیر دوم/بازیابی رمز مدیر نداریم؛ backup امن DB و دسترسی عملیاتی سرور لازم است.

مثال اجرای local HTTP فقط روی loopback، با همان DB ساخته‌شده:

```sh
SPD_DATABASE_PATH=/absolute/private/data/premium.sqlite \
SPD_ADMIN_ORIGIN=http://127.0.0.1:8080 \
SPD_ADMIN_INSECURE_LOCAL=true \
  java -jar build/libs/spd-vpn-backend-all.jar -host=127.0.0.1 -port=8080
```

در production، origin دقیق HTTPS را تنظیم و `SPD_ADMIN_INSECURE_LOCAL=false` نگه دارید.
نام host/port مرورگر باید دقیقاً با origin تنظیم‌شده یکی باشد؛ مثلاً localhost و 127.0.0.1
دو origin جدا هستند. cookie مدیر HttpOnly، SameSite=Strict، Path=/admin و به‌صورت پیش‌فرض Secure است.
همهٔ mutationهای مدیریتی، حتی login، Origin را بررسی می‌کنند؛ mutationهای نشست‌دار علاوه بر آن
`X-CSRF-Token` می‌خواهند. توکن CSRF فقط در حافظهٔ صفحه است؛ cookie نشست برای JavaScript قابل خواندن نیست.

## تنظیمات بدون secret

`.env.example` نمونهٔ placeholder است؛ در اجرای مستقیم Java فایل `.env` خودکار load نمی‌شود.
credential واقعی را از environment یا secret injection سرویس بدهید و فایل env عملیاتی را خارج Git با دسترسی محدود نگه دارید.
برای بررسی compose فقط `docker compose config --quiet` اجرا کنید؛ خروجی کامل env می‌تواند secret را نشان بدهد.

| متغیر | مقدار پیش‌فرض / کاربرد |
| --- | --- |
| SPD_DATABASE_PATH | `data/premium.sqlite`؛ compose از volume فعلی `/app/data` استفاده می‌کند |
| PANEL_CONTENT_PATH | کنار DB، `panel-content.json` |
| SPD_ADMIN_ORIGIN | `https://localhost:8080`؛ در production دامنهٔ واقعی خودتان |
| SPD_ADMIN_INSECURE_LOCAL | false؛ true فقط برای origin و peer از loopback |
| SPD_ADMIN_SESSION_SECONDS | 43200؛ انقضای مطلق نشست مدیر، قابل revoke با logout |
| SPD_ACCESS_TOKEN_SECONDS | 900؛ مستقل از اعتبار اشتراک |
| SPD_REFRESH_TOKEN_SECONDS | 2592000؛ rotation و ابطال access قبلی در refresh |
| SPD_DEFAULT_QUOTA_BYTES | 107374182400، یعنی 100 GiB؛ فقط seed پلن‌های تازه |
| PASARGUARD_BASE_URL | خالی تا تنظیم نمونهٔ مجاز؛ مثل `https://vpn-control.example.invalid` |
| PASARGUARD_USERNAME / PASARGUARD_PASSWORD | هر دو همراه URL لازم؛ هرگز داخل اپ یا پاسخ API نیستند |
| PASARGUARD_GROUP_IDS | شناسهٔ گروه‌های **واقعاً موجود** و قابل دسترسی برای مدیر اختصاصی |
| PASARGUARD_TIMEOUT_MS | 10000؛ محدودهٔ مجاز 1000 تا 60000 |
| PASARGUARD_ALLOW_LOCAL_HTTP | false؛ true فقط برای loopback توسعه/تست |

بدون تنظیم PasarGuard، محصول `premiumVpnConfigured=false` است؛ ساخت حساب می‌تواند ثبت شود ولی
عملیات همگام‌سازی با کد `PASARGUARD_NOT_CONFIGURED` ناموفق می‌ماند. موفقیت VPN یا دادهٔ mock جعل نمی‌شود.
APIهای رایگان در همین حالت کار می‌کنند. اگر فقط بخشی از credentialها تنظیم شوند، startup با خطای تنظیمات متوقف می‌شود.

## کاربری پنل

۱. در کاربران، نام و رمز ورود اپ و پلن را ثبت کنید. پاسخ 202 یعنی عملیات ثبت‌شده/PENDING، نه تکمیل PasarGuard.
۲. جزئیات، درخواست SPD و وضعیت تأییدشدهٔ VPN را جدا نشان می‌دهد. SYNCED تنها پس از خواندن تأییدی است.
۳. تمدید زمانی از `max(serverNow, expiresAt)` محاسبه می‌شود؛ تغییر پلن از زمان فعلی شروع می‌شود.
   مقادیر دقیق اولیه ۳۰ و ۹۰ روزند و مدیر می‌تواند مدت پلن را تغییر دهد. تغییر تعریف، حساب‌های قبلی را تغییر نمی‌دهد.
۴. LIFETIME فقط انقضای زمانی ندارد؛ سهمیه مستقل و پیش‌فرض محدود است. null سهمیه یعنی نامحدود؛
   نبود observation یعنی نامشخص. تبدیل دائمی به زمانی تأیید جدا می‌خواهد.
۵. غیرفعال‌سازی محلی فوراً دریافت پروفایل را منع می‌کند؛ غیرفعال‌شدن خود VPN فقط پس از تأیید PasarGuard قطعی است.
   فعال‌سازی مجدد تاریخ را تمدید نمی‌کند. حساب منقضی/غیرفعال می‌تواند وارد اپ شود و وضعیت را ببیند.
۶. تغییر رمز اپ تمام نشست‌های آن حساب را باطل می‌کند، ولی credential اجرایی VPN را عوض نمی‌کند.
۷. اتصال به کاربر موجود PasarGuard شناسهٔ عددی و تأیید صریح می‌خواهد؛ تاریخ و مصرف آن را حفظ می‌کند.
   یک id نمی‌تواند به دو حساب SPD متصل شود. اتصال ناموفقِ تأییدنشده قابل اصلاح صریح است.
۸. در FAILED، کد خطا و داده‌های واقعی را بررسی و سپس retry/تطبیق را تأیید کنید. عملیات معلق پیش از ثبت تغییر بعدی باید تعیین تکلیف شود.
   پیش از ثبت تغییرِ حساب تأییدشده، وضعیت واقعی دوباره خوانده می‌شود؛ اگر سرویس در دسترس نباشد،
   درخواست جدید ثبت نمی‌شود و خطای 503 برمی‌گردد. تعارض بیرونی نیز پیش از بازنویسی نیاز به تأیید تطبیق دارد.
۹. به‌روزرسانی Free از همان pipeline و lock استفاده می‌کند؛ در حال اجرا یا با فاصلهٔ کمتر از ۳۰ ثانیه، درخواست جدید رد می‌شود.

نام کاربری canonical به lowercase لاتین تبدیل می‌شود؛ ۳ تا ۶۴ نویسهٔ `[a-z0-9_.-]` با نویسهٔ اول حرف/عدد.
نام SPD با نام deterministic کاربر VPN فرق دارد. رمز قابل بازیابی/نمایش نیست.

## امنیت و محدودیت عملیاتی

- Argon2id v19 با 64 MiB، سه pass و p=1؛ salt تصادفی برای هر رمز، حداکثر دو hash هم‌زمان.
- access/refresh و cookie نشست تصادفی ۲۵۶ بیتی‌اند؛ در DB فقط hash توکن نشست ذخیره می‌شود.
  CSRF verifier در DB است. TLS برای انتقال ضروری است؛ DB رمزگذاری‌شده در سطح SQL نیست و backup/دیسک باید محدود باشد.
- login از socket peer واقعی و نام canonical rate-limit می‌شود؛ forwarded header دلخواه مبنا نیست.
  محدودیت نام: ۵ تلاش ناموفق در ۱۵ دقیقه (موفقیت آن را پاک می‌کند)، peer: ۲۰، scope: ۱۰۰ تلاش در همان بازه.
  پشت proxy، محدودیت peer بین کاربران مشترک می‌شود؛ برای مقیاس بالاتر، سیاست proxy قابل اعتماد باید جدا طراحی شود.
- audit فقط actor/action/id/time و کد غیرحساس دارد؛ رمز، token، body و کانفیگ خصوصی ثبت نمی‌شود.
  مسیرهای خصوصی از CallLogging کنار گذاشته‌اند؛ خطای upstream به پیام و کد ثابت تبدیل می‌شود.
- body ورودی ۱۶ KiB، body upstream دو MiB؛ صفحات ۱ تا ۱۰۰ رکورد. APIهای خصوصی `Cache-Control: no-store` دارند.
- authorization همیشه owner را از نشست می‌گیرد و وضعیت فعلی سرور را می‌خواند؛ ارسال userId دلخواه مالکیت را عوض نمی‌کند.
- quota exhausted به انقضای زمانی جعلی تبدیل نمی‌شود؛ profile می‌تواند ACTIVE زمانی ولی `premiumVpnConfigured=false` باشد.
- SQLite و worker این نسخه برای یک instance هستند. backup هنگام توقف سرویس یا با ابزار backup سازگار SQLite تهیه شود؛
  پیش از migrationهای بعدی، DB و فایل محتوای پنل را همراه image قبلی قابل بازیابی نگه دارید. snapshot کاتالوگ را پاک نکنید.
- این فاز engine enforcement اندروید در background، کنترل تعداد دستگاه یا لغو credential تمام کلاینت‌های خارجی را اضافه نمی‌کند.
  اجرای تغییرات زیرساخت واقعی VPN بر عهدهٔ PasarGuard است؛ وضعیت تأییدنشده به‌صورت موفق نمایش داده نمی‌شود.

## آزمون مرورگر

`scripts/admin-browser-test.mjs` فقط یک backend واقعی و disposable روی `127.0.0.1` را می‌پذیرد.
با DB موقت جدا، مدیر bootstrap‌شده و password file موقت با mode 0600 اجرا کنید:

```sh
SPD_E2E_BASE_URL=http://127.0.0.1:18765 \
SPD_E2E_PASSWORD_FILE=/absolute/private/tmp/test-password \
SPD_BROWSER_EXECUTABLE='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
  npm run test:browser
```

این تست حساب نمونه در **DB موقت** می‌سازد؛ بدون PasarGuard، FAILED واقعی را انتظار دارد.
در production یا با DB واقعی اجرا نکنید. بدون executable موجود، Chromium تست Playwright را جدا نصب کنید.
تصاویر `screenshots/` حاصل همین تست‌اند؛ توضیحات و نتایج نهایی در [verification.md](verification.md) ثبت می‌شوند.
