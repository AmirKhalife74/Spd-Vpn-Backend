# انتشار بک‌اند SPD VPN روی VPS

این پروژه برای انتشار به Node.js، نصب Java روی سرور یا تنظیم Gradle نیاز ندارد. تمام build و اجرای برنامه داخل Docker انجام می‌شود.

## پیش‌نیازها

- یک VPS خارج از ایران با Ubuntu 24.04
- حداقل 1 vCPU و 1 GB RAM
- Docker Engine و Docker Compose Plugin
- یک دامنه مانند `api.spdvpn.app` که به IP سرور اشاره کند
- باز بودن پورت‌های 80 و 443

## اجرای اولیه

پروژه را روی سرور قرار بده و در پوشه اصلی آن اجرا کن:

```bash
cp .env.example .env
docker compose up --build -d
docker compose ps
curl http://127.0.0.1:8080/v1/health
```

در اولین اجرا Docker برنامه را build می‌کند، تست‌ها را اجرا می‌کند و سپس سرویس را بالا می‌آورد. API عمداً فقط روی `127.0.0.1:8080` در دسترس است تا مستقیماً از اینترنت قابل دسترسی نباشد.

برای فعال‌کردن endpoint داخلی گزارش probe، در `.env` یک مقدار تصادفی و طولانی برای `PROBER_SHARED_SECRET` قرار بده. همین مقدار به API و prober داده می‌شود. تا وقتی این مقدار خالی است endpoint با `404` غیرفعال می‌ماند. مقدار واقعی secret را داخل سورس یا compose ثبت نکن.

اجرای معمول زیر فقط API را بالا می‌آورد و prober را فعال نمی‌کند:

```bash
docker compose up --build -d
```

قبل از هر probe واقعی، یک دور dry-run اجرا کن. dry-run فقط فهرست را می‌گیرد، profileهای انتخاب‌شده را با محدودیت نرخ دانلود و اعتبارسنجی می‌کند و نه OpenVPN اجرا می‌کند و نه نتیجه‌ای می‌فرستد:

```bash
PROBER_DRY_RUN=true PROBER_RUN_ONCE=true \
  docker compose --profile prober run --rm prober
```

بعد از بررسی خروجی خلاصه و تنظیم secret، اجرای زمان‌بندی‌شده با دستور زیر فعال می‌شود. این دستور اتصال واقعی خروجی به سرورهای داوطلب VPN Gate برقرار می‌کند، پس فقط با مجوز عملیاتی اجرا شود:

```bash
docker compose --profile prober up --build -d
docker compose --profile prober ps
docker compose --profile prober logs -f --tail=100 prober
```

## فعال‌کردن HTTPS با Caddy

Caddy را روی میزبان نصب کن، مقدار دامنه را در `Caddyfile.example` تغییر بده و فایل را به تنظیمات Caddy منتقل کن. سپس Caddy را reload کن. Caddy گواهی TLS را خودکار دریافت و تمدید می‌کند و درخواست‌ها را به کانتینر می‌فرستد. matcher موجود در نمونه، مسیر `/internal` و زیرمسیرهایش را با `404` می‌بندد؛ آن را حذف نکن.

بعد از فعال‌شدن HTTPS این آدرس‌ها باید پاسخ بدهند:

```text
https://api.spdvpn.app/health/live
https://api.spdvpn.app/v1/health
https://api.spdvpn.app/v1/countries
https://api.spdvpn.app/v1/servers?protocol=UDP&limit=20
```

## به‌روزرسانی نسخه

بعد از جایگزین‌کردن سورس با نسخه جدید:

```bash
docker compose up --build -d
docker image prune -f
```

## مشاهده لاگ و عیب‌یابی

```bash
docker compose logs -f --tail=200 api
docker compose --profile prober logs -f --tail=200 prober
docker compose ps
curl -i http://127.0.0.1:8080/v1/health
```

اگر VPN Gate موقتاً در دسترس نباشد، سرویس آخرین cache سالم را برمی‌گرداند و وضعیت health به `degraded` تغییر می‌کند. اگر هنوز هیچ cache سالمی ساخته نشده باشد، endpointهای catalog پاسخ `503` می‌دهند.

## داده پایدار

آخرین catalog سالم و snapshot مستقل سلامت سرورها در volume با نام `spdvpn-data` نگهداری می‌شوند. مسیر پیش‌فرض سلامت `/app/data/server-health.json` است. این فایل‌ها با rebuild یا تعویض کانتینر حذف نمی‌شوند. برای این فاز دیتابیس لازم نیست.

## نکات امنیتی

- پورت 8080 را عمومی نکن؛ فقط 80 و 443 باید از اینترنت باز باشند.
- اگر API پشت Cloudflare قرار می‌گیرد، headerهای IP forwarding را فقط از reverse proxy قابل اعتماد بپذیر.
- برای `CORS_ALLOWED_HOSTS` در نسخه وب، دامنه‌های واقعی را وارد کن. کلاینت Android به CORS نیاز ندارد.
- مقدار refresh را خیلی پایین نگذار؛ پیش‌فرض 5 دقیقه برای این کاربرد مناسب است.
- header با نام `X-SpdVpn-Prober-Token` را فقط worker مورد اعتماد ارسال کند و مقدار آن را در لاگ یا پاسخ خطا قرار نده.
- OpenVPN فقط در image جداگانه prober نصب می‌شود؛ کانتینر API آن را ندارد و اجرا نمی‌کند.
- برای prober گزینه‌های `privileged`، `NET_ADMIN`، host network، Docker socket، volume میزبان یا `/dev/net/tun` اضافه نکن.
- prober به‌صورت non-root، با حذف همه capabilityها، root filesystem فقط‌خواندنی و `/tmp` محدود اجرا می‌شود. این probe فقط رسیدن OpenVPN به مرحله CONNECTED را می‌سنجد و ترافیک را tunnel نمی‌کند.
- خطای API، محدودیت نرخ، خرابی دانلود config، JSON نامعتبر، لغو worker یا مشکل شروع process نباید به‌عنوان خرابی سرور ثبت شود.
