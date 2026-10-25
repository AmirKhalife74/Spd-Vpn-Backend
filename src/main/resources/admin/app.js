"use strict";
// Reuses the existing same-origin, static Ktor panel structure. Remote text is never HTML.
const $ = id => document.getElementById(id);
const titles = {dashboard:"نمای کلی",users:"کاربران و اشتراک‌ها",user:"جزئیات حساب",plans:"تنظیم پلن‌ها",free:"سرورهای رایگان",audit:"تاریخچهٔ عملیات",content:"انتشار نسخه و پیام"};
let csrf = "", generation = 0, detailTimer, toastTimer, currentPage = "dashboard";
const fa = new Intl.NumberFormat("fa-IR");
const date = value => value == null ? "بدون انقضای زمانی" : new Date(value).toLocaleString("fa-IR");
const quota = (value, known = true) => !known ? "نامشخص" : value == null ? "نامحدود" : fa.format(value / 1073741824) + " GiB";
const usage = value => value == null ? "نامشخص" : fa.format(value / 1073741824) + " GiB";
const syncNames = {PENDING:"در انتظار همگام‌سازی",SYNCED:"تأیید شده",FAILED:"نیاز به بررسی"};
function node(tag, attrs = {}, children = []) {
  const e = document.createElement(tag);
  for (const [key,value] of Object.entries(attrs)) {
    if (key.startsWith("on")) e.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === "className") e.className = value;
    else if (key === "text") e.textContent = value;
    else if (key in e) e[key] = value;
    else e.setAttribute(key, value);
  }
  for (const child of [children].flat()) if (child != null) e.append(typeof child === "string" ? document.createTextNode(child) : child);
  return e;
}
const text = (tag,value,cls="") => node(tag,{text:String(value),className:cls});
const badge = state => text("span",syncNames[state] || state,"badge "+state);
const card = (title,children) => node("section",{className:"card"},[text("h2",title),...children]);
const notice = message => text("div",message,"notice");
function toast(message,error=false) {
  $("toast").textContent=message; $("toast").className="toast"+(error?" error":"");
  clearTimeout(toastTimer);toastTimer=setTimeout(()=>$("toast").classList.add("hidden"),7000);
}
function responseFailure(status) {
  if(status===403)return "درخواست مدیریتی توسط سرور یا درگاه رد شد (HTTP 403). صفحه را تازه‌سازی کن و دوباره وارد شو؛ اگر ادامه داشت با مدیر سرور تماس بگیر.";
  if(status===401)return "نشست مدیریت معتبر نیست یا منقضی شده است (HTTP 401)؛ دوباره وارد شو.";
  if(status===429)return "درخواست‌های زیادی ارسال شده است (HTTP 429)؛ کمی بعد دوباره تلاش کن.";
  if(status>=500)return `سرویس موقتاً در دسترس نیست (HTTP ${status})؛ کمی بعد دوباره تلاش کن.`;
  return `پاسخ سرویس در قالب مورد انتظار نیست (HTTP ${status})؛ صفحه را تازه‌سازی کن و دوباره تلاش کن.`;
}
async function api(path,method="GET",body,key) {
  const requestSession=csrf;
  const headers={Accept:"application/json"};
  if(method!=="GET") {headers["Content-Type"]="application/json";if(csrf)headers["X-CSRF-Token"]=csrf;}
  if(key)headers["Idempotency-Key"]=key;
  let response;
  try{response=await fetch("/admin/api/"+path,{method,headers,credentials:"same-origin",cache:"no-store",signal:AbortSignal.timeout(70000),body:body===undefined?undefined:JSON.stringify(body)});}
  catch{throw new Error("پاسخ سرور دریافت نشد؛ اتصال را بررسی و با همان فرم دوباره تلاش کنید");}
  if(response.status===401 && path!=="login" && path!=="session" && requestSession===csrf) showLogin();
  let data;try{data=await response.json();}catch{throw new Error(responseFailure(response.status));}
  if(data===null || typeof data!=="object" || typeof data.success!=="boolean" ||
    (data.success && !Object.hasOwn(data,"data")))throw new Error(responseFailure(response.status));
  if(!response.ok || data.success!==true) {
    const code=data.error?.code;
    if(code==="CSRF_INVALID")throw new Error("اعتبار امنیتی درخواست معتبر نیست (HTTP 403)؛ صفحه را تازه‌سازی کن و دوباره وارد شو.");
    if(code==="ORIGIN_INVALID")throw new Error("مبدأ درخواست مدیریتی معتبر نیست (HTTP 403)؛ پنل را از آدرس اصلی و امن آن باز کن.");
    throw new Error(typeof data.error?.message==="string" && data.error.message ? data.error.message : responseFailure(response.status));
  }
  return data.data;
}
function showLogin() {csrf="";clearTimeout(detailTimer);generation++;$("workspace").classList.add("hidden");$("login").classList.remove("hidden");}
function showWorkspace(session) {csrf=session.csrfToken;$("admin-name").textContent=session.username;$("login").classList.add("hidden");$("workspace").classList.remove("hidden");navigate();}
function button(label,action,cls="") {
  const b=node("button",{type:"button",text:label,className:cls});
  b.addEventListener("click",async()=>{if(b.disabled)return;b.disabled=true;try{await action();}catch(e){toast(e.message,true);}finally{b.disabled=false;}});
  return b;
}
function field(label,name,type="text",value="",extra={}) {return node("label",{},[label,node("input",{name,type,value,...extra})]);}
function selectField(label,name,options) {return node("label",{},[label,node("select",{name},options.map(([value,label])=>node("option",{value,text:label})))]);}
function form(children,submitLabel,action) {
  const f=node("form",{className:"stack"},[...children,node("button",{type:"submit",className:"primary",text:submitLabel})]);
  f.addEventListener("submit",async event=>{
    event.preventDefault();const b=f.querySelector('[type="submit"]');if(b.disabled)return;b.disabled=true;
    try{await action(new FormData(f),f);}catch(e){toast(e.message,true);}finally{b.disabled=false;}
  });return f;
}
function details(values) {return node("dl",{},values.flatMap(([label,value])=>[text("dt",label),text("dd",value)]));}
function table(headers,rows) {return node("div",{className:"table-wrap"},node("table",{},[
  node("thead",{},node("tr",{},headers.map(h=>text("th",h)))),
  node("tbody",{},rows.length?rows.map(row=>node("tr",{},row.map(value=>node("td",{},value)))):node("tr",{},node("td",{colSpan:headers.length,className:"empty",text:"هنوز داده‌ای وجود ندارد"}))) ]));}
function pagination(total,offset,limit,action) {const prev=button("قبلی",()=>action(Math.max(0,offset-limit)));prev.disabled=offset===0;const next=button("بعدی",()=>action(offset+limit));next.disabled=offset+limit>=total;return node("div",{className:"pagination"},[prev,text("small",`${fa.format(total)} رکورد`),next]);}
function render(children,expected) {if(expected!==generation)return;$("content").replaceChildren(...children);}
async function navigate() {
  clearTimeout(detailTimer);const expected=++generation;
  const [requested,id] = location.hash.slice(1).split("/");
  const page=Object.hasOwn(titles,requested)?requested:"dashboard";currentPage=page;
  $("page-title").textContent=titles[page]||titles.dashboard;
  document.querySelectorAll("nav a").forEach(a=>a.classList.toggle("active",a.dataset.page===(page==="user"?"users":page)));
  render([text("div","در حال دریافت داده…","loading")],expected);
  try{await ({dashboard:()=>dashboard(expected),users:()=>users(expected),user:()=>user(id,expected),plans:()=>plans(expected),free:()=>free(expected),audit:()=>audit(expected),content:()=>content(expected)})[page]?.();}
  catch(e){render([text("div",e.message,"error-box"),button("تلاش دوباره",navigate)],expected);}
}
async function dashboard(g) {
  const d=await api("dashboard"),a=d.accounts,p=d.activity.presence;
  const metrics=[["کل حساب‌های SPD",a.users],["غیرفعال",a.disabled],["در انتظار همگام‌سازی",a.pending],["نیازمند بررسی",a.failed]];
  render([text("p","وضعیت حساب‌ها و آخرین داده‌های ثبت‌شدهٔ سرویس.","muted"),
    ...(!d.premiumVpnConfigured?[notice("اتصال سرویس VPN هنوز تنظیم نشده است. حساب قابل ثبت است، اما همگام‌سازی تا تنظیم اتصال تأیید نمی‌شود.")]:[]),
    node("div",{className:"metrics"},metrics.map(([label,value])=>node("article",{className:"metric"},[text("span",label),text("strong",fa.format(value))]))),
    card("اتصال‌های گزارش‌شده",[details([["session فعال با TTL",fa.format(p.activeConnections)],["نصب یکتای فعال",fa.format(p.uniqueActiveInstallations)],["زمان محاسبه",date(p.generatedAtEpochMillis)]]),
      text("p","این آمار از heartbeat کلاینت به دست می‌آید، خوداظهاری است و معادل تعداد کاربران احراز هویت‌شده یا اتصال قطعی PasarGuard نیست.","status-note")]),
    card("مسیر مدیریت",[text("p","حساب ورود در SPD و دسترسی اجرایی VPN در PasarGuard مدیریت می‌شود. ساخت حساب تا تأیید همگام‌سازی، کامل محسوب نمی‌شود.","muted"),
      node("div",{className:"actions"},[button("مشاهدهٔ کاربران",()=>{location.hash="users";},"primary"),button("بروزرسانی",navigate)])])],g);
}
async function users(g,offset=0,q="") {
  const [page,planList]=await Promise.all([api(`users?limit=20&offset=${offset}&q=${encodeURIComponent(q)}`),api("plans")]);
  const eligible=assignablePlans(planList);
  const search=form([field("جست‌وجوی نام کاربری","q","search",q,{maxlength:128})],"جست‌وجو",data=>users(g,0,String(data.get("q"))));
  const requestKey=crypto.randomUUID();
  const create=form([field("نام کاربری (لاتین)","username","text","",{required:true,minLength:3,maxLength:64,dir:"ltr",autocomplete:"off"}),
    field("رمز ورود اپ","password","password","",{required:true,minLength:12,maxLength:256,autocomplete:"new-password"}),
    selectField("پلن","planId",eligible.map(p=>[p.id,p.title])),
    field("شناسهٔ حساب موجود PasarGuard (اختیاری)","existingId","number","",{min:1}),
    node("label",{className:"check"},[node("input",{name:"confirm",type:"checkbox"}),"اتصال به حساب VPN موجود را تأیید می‌کنم"]),
    text("small","برای حساب جدید، اعتبار از زمان ساخت آغاز می‌شود. اتصال به حساب موجود، تاریخ و سقف حجم واقعی آن را حفظ می‌کند.")],"ثبت حساب و درخواست همگام‌سازی",async(data,f)=>{
      const password=String(data.get("password"));
      try{const item=await api("users","POST",{username:data.get("username"),password,planId:data.get("planId"),existingPasarGuardUserId:data.get("existingId")?Number(data.get("existingId")):null,confirmExistingUser:data.get("confirm")==="on"},requestKey);
        toast("درخواست ثبت شد؛ نتیجهٔ همگام‌سازی را در جزئیات ببینید");location.hash="user/"+item.id;
      }finally{f.elements.password.value="";}
    });
  create.querySelector('[type="submit"]').disabled=eligible.length===0;
  if(!eligible.length)create.prepend(notice("پلن فعال و قابل تخصیص وجود ندارد؛ ابتدا تنظیمات پلن‌ها را بررسی کنید."));
  render([node("div",{className:"two-columns"},[card("حساب‌های کاربران",[search,table(["کاربر","همگام‌سازی","انقضا","وضعیت اپ",""],page.items.map(a=>[
    text("b",a.username,"ltr"),badge(a.syncState),date(a.requested.expiresAtEpochMillis),a.requested.disabled?"غیرفعال":"فعال‌سازی مجاز پس از تأیید",button("جزئیات",()=>{location.hash="user/"+a.id;})])),pagination(page.total,offset,20,next=>users(g,next,q))]),card("ساخت حساب",[create])])],g);
}
async function user(id,g) {
  if(!/^[a-f0-9-]{36}$/.test(id||""))throw new Error("شناسهٔ حساب معتبر نیست");
  const [a,ops,planList]=await Promise.all([api("users/"+id),api(`users/${id}/operations`),api("plans")]);
  const eligible=assignablePlans(planList);
  const r=a.confirmed;const pending=a.syncState!=="SYNCED";
  const subscriptionKey=crypto.randomUUID(),toggleKey=crypto.randomUUID();
  const subscription=form([selectField("عملیات","action",[["RENEW","تمدید با حفظ اعتبار باقی‌مانده"],["CHANGE_PLAN","تغییر پلن از زمان فعلی"]]),
    selectField("پلن","planId",[["","پلن قابل تخصیص را انتخاب کنید"],...eligible.map(p=>[p.id,p.title])]),
    node("label",{className:"check"},[node("input",{name:"convert",type:"checkbox"}),"تبدیل دائمی به زمانی را صریحاً تأیید می‌کنم"]),
    text("small","سقف حجم کل با پلن جایگزین می‌شود. مصرف قبلی خودکار صفر نمی‌شود. زمان‌ها از سرور محاسبه می‌شوند.")],"ثبت تغییر اشتراک",async data=>{
      if(!confirm("تغییر اشتراک و سقف حجم این حساب ثبت شود؟"))return;
      await api(`users/${id}/subscription`,"POST",{action:data.get("action"),planId:data.get("planId"),confirmLifetimeToTimed:data.get("convert")==="on"},subscriptionKey);toast("درخواست تغییر ثبت شد؛ منتظر تأیید بمانید");await user(id,g);
    });subscription.elements.planId.required=true;
  subscription.elements.planId.value=eligible.some(p=>p.id===a.planId)?a.planId:"";
  subscription.querySelector('[type="submit"]').disabled=pending||eligible.length===0;
  if(!eligible.length)subscription.prepend(notice("پلن فعال و قابل تخصیص وجود ندارد. اشتراک فعلی حساب بدون تغییر باقی می‌ماند."));
  const password=form([field("رمز جدید ورود اپ","password","password","",{required:true,minLength:12,maxLength:256,autocomplete:"new-password"})],"تغییر رمز و ابطال نشست‌های اپ",async(data,f)=>{
    try{if(!confirm("رمز ورود اپ تغییر کند و نشست‌های قبلی باطل شوند؟"))return;await api(`users/${id}/password`,"POST",{password:data.get("password")});toast("رمز تغییر کرد؛ نشست‌های اپ باطل شدند");}finally{f.elements.password.value="";}
  });
  const toggle=button(a.requested.disabled?"فعال‌سازی مجدد":"غیرفعال‌کردن حساب",async()=>{
    if(!confirm(a.requested.disabled?"حساب بدون تمدید زمان دوباره فعال شود؟":"حساب SPD و دسترسی VPN غیرفعال شوند؟"))return;
    await api(`users/${id}/subscription`,"POST",{action:"SET_DISABLED",disabled:!a.requested.disabled},toggleKey);toast("درخواست ثبت شد؛ وضعیت VPN پس از همگام‌سازی تأیید می‌شود");await user(id,g);
  },a.requested.disabled?"":"danger");toggle.disabled=pending;
  const repairKey=crypto.randomUUID();
  const repair=a.syncState==="FAILED"&&!r&&ops[0]?.kind==="LINK"?card("اصلاح اتصال تأییدنشده",[form([
    field("شناسهٔ درست کاربر PasarGuard","pgId","number",a.pasarGuardUserId||"",{required:true,min:1}),
    selectField("نوع پلن متناسب با حساب موجود","planId",eligible.map(p=>[p.id,p.title]))],"اصلاح و اعتبارسنجی اتصال",async data=>{
      if(!confirm("شناسهٔ حساب VPN تأییدنشده جایگزین شود؟ اعتبار واقعی آن پس از خواندن حفظ می‌شود."))return;
      await api(`users/${id}/link`,"POST",{pasarGuardUserId:Number(data.get("pgId")),planId:data.get("planId"),confirm:true},repairKey);await user(id,g);
    })]):null;
  if(repair&&!eligible.length)repair.querySelector('[type="submit"]').disabled=true;
  render([node("div",{className:"toolbar"},[node("div",{className:"detail-heading"},[text("h2",a.username,"ltr"),badge(a.syncState)]),button("بازگشت به کاربران",()=>{location.hash="users";})]),
    ...(a.syncState==="FAILED"?[notice("عملیات کامل نشده است. مقدار درخواستی و تأییدشده را بررسی کنید. کد غیرحساس: "+(a.lastErrorCode||"نامشخص"))]:[]),
    ...(repair?[repair]:[]),
    node("div",{className:"two-columns"},[card("درخواست SPD",[details([["پلن",a.planId],["ساخت حساب",date(a.createdAtEpochMillis)],["انقضا",date(a.requested.expiresAtEpochMillis)],["سقف حجم کل",quota(a.requested.quotaBytes)],["وضعیت درخواستی",a.requested.disabled?"غیرفعال":"فعال"]])]),
      card("وضعیت خوانده‌شده از PasarGuard",[details([["شناسهٔ پایدار",a.pasarGuardUserId??"هنوز مرتبط نشده"],["وضعیت VPN",r?.status??"نامشخص"],["انقضا",r?date(r.expiresAtEpochMillis):"نامشخص"],["سقف حجم",quota(r?.quotaBytes,!!r)],["مصرف",usage(r?.usedBytes)],["آخرین مشاهده",a.lastObservedAtEpochMillis?date(a.lastObservedAtEpochMillis):"هنوز خوانده نشده"]])])]),
    node("div",{className:"actions"},[toggle,button("خواندن وضعیت واقعی",async()=>{await api(`users/${id}/refresh`,"POST",{});await user(id,g);}),
      ...(a.syncState==="FAILED"?[button("تلاش دوباره / تطبیق با SPD",async()=>{if(!confirm("عملیات ذخیره‌شده دوباره تطبیق داده شود؟ مقدار درخواستی SPD ممکن است روی PasarGuard اعمال شود."))return;await api(`users/${id}/retry`,"POST",{});await user(id,g);})]:[])]),
    node("div",{className:"two-columns"},[card("تمدید و تغییر پلن",[subscription]),card("امنیت حساب",[text("p","رمز قبلی قابل مشاهده یا بازیابی نیست. تغییر رمز اپ، credential اجرایی VPN را تغییر نمی‌دهد.","muted"),password])]),
    card("عملیات همگام‌سازی",[table(["عملیات","وضعیت","تلاش","زمان","کد خطا"],ops.map(o=>[o.kind,badge(o.state),fa.format(o.attempts),date(o.createdAtEpochMillis),o.errorCode||"—"]))])],g);
  if(a.syncState==="PENDING"&&g===generation)detailTimer=setTimeout(()=>user(id,g).catch(e=>toast(e.message,true)),5000);
}
function assignablePlans(items) {return items.filter(p=>p.enabled!==false&&p.isAssignable!==false);}
function planEditor(p,creating,onSaved) {
  const editor=form([
    ...(creating?[field("شناسهٔ ثابت پلن (لاتین؛ بعداً قابل تغییر نیست)","id","text","",{required:true,maxLength:64,pattern:"[A-Za-z0-9][A-Za-z0-9_-]{0,63}",dir:"ltr"})]:[text("small","شناسهٔ ثابت: "+p.id,"ltr")]),
    field("عنوان","title","text",p.title,{required:true,maxLength:120}),
    field("توضیح پیش‌فرض","description","text",p.description,{maxLength:500}),
    field("عنوان فارسی (اختیاری)","titleFa","text",p.localizedTitles?.fa??"",{maxLength:120}),
    field("عنوان انگلیسی (اختیاری)","titleEn","text",p.localizedTitles?.en??"",{maxLength:120}),
    field("توضیح فارسی (اختیاری)","descriptionFa","text",p.localizedDescriptions?.fa??"",{maxLength:500}),
    field("توضیح انگلیسی (اختیاری)","descriptionEn","text",p.localizedDescriptions?.en??"",{maxLength:500}),
    node("label",{className:"check"},[node("input",{type:"checkbox",name:"lifetime",checked:p.lifetime}),"بدون انقضای زمانی (حجم مستقل است)"]),
    field("مدت دقیق به روز (۲۴ ساعت)","days","number",p.durationDays??"",{required:!p.lifetime,disabled:p.lifetime,min:1,max:3650,step:1}),
    field("سقف حجم کل به بایت؛ خالی = نامحدود","quota","number",p.quotaBytes??"",{min:1,max:Number.MAX_SAFE_INTEGER,step:1,dir:"ltr"}),
    text("small","واحد سهمیه بایت است: 10 GB = 10000000000 بایت؛ 10 GiB = 10737418240 بایت. سقف کل مصرف است و با تمدید خودکار reset نمی‌شود."),
    field("قیمت در کوچک‌ترین واحد ارز؛ خالی = تنظیم نشده","priceMinor","number",p.priceMinor??"",{min:0,max:Number.MAX_SAFE_INTEGER,step:1,dir:"ltr"}),
    field("کد ISO ارز؛ مانند USD یا IRR (ریال)","currency","text",p.currency??"",{maxLength:3,dir:"ltr"}),
    field("ترتیب نمایش؛ عدد کمتر ابتدا","displayOrder","number",p.displayOrder??0,{required:true,min:0,max:100000,step:1}),
    text("small","قیمت در کوچک‌ترین واحد ISO ارز است؛ تعیین آن پرداخت یا فعال‌سازی خودکار ایجاد نمی‌کند."),
    node("label",{className:"check"},[node("input",{type:"checkbox",name:"enabled",checked:p.enabled!==false}),"پلن فعال باشد"]),
    node("label",{className:"check"},[node("input",{type:"checkbox",name:"published",checked:p.published!==false}),"در فهرست عمومی اپ منتشر شود (حداکثر سه پلن فعال)"]),
    node("label",{className:"check"},[node("input",{type:"checkbox",name:"isAssignable",checked:p.isAssignable!==false}),"مدیر بتواند این پلن را دستی تخصیص دهد"])
  ],creating?"ساخت پلن":"ذخیرهٔ تعریف پلن",async data=>{
    const localized=(fa,en)=>Object.fromEntries([["fa",data.get(fa)],["en",data.get(en)]].map(([key,value])=>[key,String(value).trim()]).filter(([,value])=>value));
    const integer=(name,nullable=false)=>{
      const raw=data.get(name);
      if(nullable&&raw==="")return null;
      const value=Number(raw);
      if(!Number.isSafeInteger(value))throw new Error("مقدار عددی باید صحیح و در محدودهٔ مجاز باشد");
      return value;
    };
    const lifetime=data.get("lifetime")==="on";
    const value={...p,id:creating?String(data.get("id")).trim():p.id,title:String(data.get("title")).trim(),description:String(data.get("description")),
      lifetime,durationDays:lifetime?null:integer("days"),quotaBytes:integer("quota",true),
      enabled:data.get("enabled")==="on",published:data.get("published")==="on",isAssignable:data.get("isAssignable")==="on",
      priceMinor:integer("priceMinor",true),currency:String(data.get("currency")).trim().toUpperCase()||null,
      displayOrder:integer("displayOrder"),localizedTitles:localized("titleFa","titleEn"),localizedDescriptions:localized("descriptionFa","descriptionEn")};
    await api(creating?"plans":"plans/"+p.id,creating?"POST":"PUT",value);
    toast(creating?"پلن ساخته شد":"تعریف پلن ذخیره شد");await onSaved();
  });
  editor.elements.lifetime.addEventListener("change",()=>{
    editor.elements.days.disabled=editor.elements.lifetime.checked;
    editor.elements.days.required=!editor.elements.lifetime.checked;
  });
  return editor;
}
async function plans(g) {
  const [items,purchase]=await Promise.all([api("plans"),api("premium-purchase")]);
  const contact=form([
    field("لینک تماس خرید Premium (HTTPS تلگرام)","premiumPurchaseUrl","url",purchase.premiumPurchaseUrl??"",{maxLength:2048,dir:"ltr",placeholder:"https://t.me/…"}),
    node("label",{className:"check"},[node("input",{name:"premiumPurchaseEnabled",type:"checkbox",checked:purchase.premiumPurchaseEnabled}),"دکمهٔ درخواست خرید در اپ فعال باشد"]),
    text("small","لینک مستقیم نام کاربری تلگرام را وارد کنید. این گزینه فقط تماس خارجی است؛ پرداخت یا فعال‌سازی خودکار اشتراک ایجاد نمی‌کند. برای غیرفعال‌کردن، تیک را بردارید.")
  ],"ذخیرهٔ مسیر تماس خرید",async data=>{
    await api("premium-purchase","PUT",{premiumPurchaseUrl:String(data.get("premiumPurchaseUrl")).trim()||null,premiumPurchaseEnabled:data.get("premiumPurchaseEnabled")==="on"});
    toast("مسیر تماس خرید ذخیره شد");
  });
  const draft={id:"",title:"",description:"",durationDays:30,lifetime:false,enabled:true,published:false,isAssignable:true,displayOrder:items.length};
  render([card("تماس برای خرید اشتراک",[contact]),notice("انتشار و تخصیص دستی مستقل‌اند. حداکثر سه پلن فعال در اپ نمایش داده می‌شود؛ تغییر تعریف، اشتراک‌های قبلی را تغییر نمی‌دهد."),
    card("ساخت پلن جدید",[planEditor(draft,true,()=>plans(g))]),
    ...items.map(p=>card(p.title,[planEditor(p,false,()=>plans(g))]))],g);
}
async function free(g) {
  const h=await api("free");render([card("کاتالوگ واقعی VPN Gate",[details([["وضعیت",h.status],["آماده",h.ready?"بله":"خیر"],["سرورها",fa.format(h.serverCount)],["آخرین بروزرسانی موفق",h.lastRefreshSuccessAtEpochMillis?date(h.lastRefreshSuccessAtEpochMillis):"نامشخص"],["cache قدیمی",h.source?.stale?"بله":h.source?"خیر":"نامشخص"]]),
    text("p","بروزرسانی از همان pipeline CSV/HTML و health موجود استفاده می‌کند. در شکست منبع، cache معتبر حفظ می‌شود.","muted"),
    button("بروزرسانی دستی سرورهای Free",async()=>{await api("free/refresh","POST",{});toast("کاتالوگ بروزرسانی شد");await free(g);},"primary")])],g);
}
async function audit(g,offset=0) {
  const page=await api(`audit?offset=${offset}&limit=25`);render([card("تاریخچهٔ عملیات مدیریتی",[table(["عامل","عملیات","شناسهٔ حساب","زمان"],page.items.map(e=>[e.actor,e.action,text("span",e.accountId||"—","ltr"),date(e.atEpochMillis)])),pagination(page.total,offset,25,next=>audit(g,next))])],g);
}
async function content(g) {
  const releases=await api("releases");
  const release=form([selectField("کانال","platform",[["ANDROID_DIRECT","اندروید مستقیم"],["ANDROID_PLAY","گوگل پلی"],["DESKTOP_MACOS","macOS"],["DESKTOP_WINDOWS","Windows"]]),
    field("Version code","code","number","",{required:true,min:1}),field("Version name","name","text","",{required:true,maxLength:64}),
    field("لینک HTTPS انتشار","url","url","",{required:true,dir:"ltr"}),field("SHA-256 فایل","sha","text","",{required:true,minLength:64,maxLength:64,dir:"ltr"}),
    field("خلاصهٔ تغییرات","changelog","text","",{maxLength:4000})],"انتشار نسخه",async data=>{
    await api("releases","POST",{platform:data.get("platform"),versionCode:Number(data.get("code")),versionName:data.get("name"),downloadUrl:data.get("url"),sha256:data.get("sha"),changelog:data.get("changelog"),minimumSupportedVersionCode:1,mandatory:false,enabled:true});toast("نسخه ثبت شد");await content(g);
  });
  const announcement=form([field("عنوان پیام","title","text","",{required:true,maxLength:120}),field("متن پیام","message","text","",{required:true,maxLength:2000}),
    selectField("شدت","severity",[["INFO","اطلاع"],["SUCCESS","موفقیت"],["WARNING","هشدار"],["CRITICAL","مهم"]])],"ثبت پیام داخل اپ",async data=>{
      await api("announcements","POST",{title:data.get("title"),message:data.get("message"),severity:data.get("severity"),platforms:[],dismissible:true,enabled:true});toast("پیام ثبت شد");
    });
  render([card("نسخه‌های ثبت‌شده",[table(["نسخه","کانال","کد","انتشار"],releases.map(r=>[r.versionName,r.platform,fa.format(r.versionCode),date(r.publishedAtEpochMillis)]))]),node("div",{className:"two-columns"},[card("نسخهٔ جدید",[release]),card("پیام عمومی",[announcement])])],g);
}
$("login-form").addEventListener("submit",async event=>{
  event.preventDefault();const f=event.currentTarget,b=f.querySelector("button");if(b.disabled)return;generation++;b.disabled=true;$("login-error").textContent="";
  try{const session=await api("login","POST",{username:f.elements.username.value,password:f.elements.password.value});showWorkspace(session);}
  catch(e){$("login-error").textContent=e.message;}finally{f.elements.password.value="";b.disabled=false;}
});
$("logout").onclick=async()=>{try{await api("logout","POST",{});}catch(e){toast(e.message,true);return;}showLogin();};
window.addEventListener("hashchange",()=>{if(csrf)navigate();});
const initialGeneration=generation;
api("session").then(session=>{if(generation===initialGeneration)showWorkspace(session);})
  .catch(()=>{if(generation===initialGeneration)showLogin();});
