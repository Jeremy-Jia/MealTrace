package dev.mealtrace.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.database.ContentObserver;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

public final class MainActivity extends Activity {
  static final int BG = Color.rgb(246, 245, 239),
      INK = Color.rgb(36, 53, 43),
      GREEN = Color.rgb(49, 109, 82),
      MUTED = Color.rgb(113, 123, 112),
      LINE = Color.rgb(226, 229, 218),
      PALE = Color.rgb(234, 239, 224);
  LinearLayout root, body, nav;
  TextView subtitle;
  ScrollView scroll;
  Prefs prefs;
  Store store;
  String date = LocalDate.now().toString();
  int page = 0, range = 7;
  boolean resumed = false, working = false;
  long detailId = -1;
  final Handler handler = new Handler(Looper.getMainLooper());
  List<Store.Meal> trashRequest = List.of();
  private final BroadcastReceiver changed =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
          if (resumed) render();
        }
      };
  final ContentObserver observer =
      new ContentObserver(handler) {
        @Override
        public void onChange(boolean self) {
          if (prefs.enabled()) {
            handler.removeCallbacks(autoScan);
            handler.postDelayed(autoScan, 6000);
          }
        }
      };
  final Runnable autoScan = () -> runScan(false);

  int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  GradientDrawable bg(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  TextView text(String s, int size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    t.setLineSpacing(dp(3), 1);
    return t;
  }

  void add(LinearLayout l, View v) {
    l.addView(v);
  }

  void gap(LinearLayout l, int h) {
    View v = new View(this);
    l.addView(v, new LinearLayout.LayoutParams(1, dp(h)));
  }

  void pad(View v, int n) {
    v.setPadding(dp(n), dp(n), dp(n), dp(n));
  }

  TextView button(String title, Runnable action) {
    TextView t = text(title, 14, GREEN, true);
    t.setGravity(Gravity.CENTER);
    t.setPadding(dp(14), dp(12), dp(14), dp(12));
    t.setBackground(bg(PALE, 14));
    t.setMinHeight(dp(46));
    t.setOnClickListener(v -> action.run());
    return t;
  }

  void actions(LinearLayout parent, String[] labels, Runnable[] callbacks) {
    LinearLayout r = row();
    for (int i = 0; i < labels.length; i++) {
      TextView b = button(labels[i], callbacks[i]);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
      if (i > 0) lp.leftMargin = dp(8);
      r.addView(b, lp);
    }
    add(parent, r);
  }

  LinearLayout card(LinearLayout parent) {
    LinearLayout l = column();
    pad(l, 18);
    l.setBackground(bg(Color.WHITE, 22));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(12);
    parent.addView(l, p);
    return l;
  }

  void heading(String title, String sub) {
    add(body, text(title, 25, INK, true));
    if (sub != null) {
      gap(body, 4);
      add(body, text(sub, 13, MUTED, false));
    }
    gap(body, 18);
  }

  String today() {
    return LocalDate.now().toString();
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    if (BuildConfig.DEBUG) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    prefs = new Prefs(this);
    store = Store.get(this);
    if (state != null) {
      page = state.getInt("page");
      date = state.getString("date", today());
    }
    root = column();
    root.setBackgroundColor(BG);
    root.setOnApplyWindowInsetsListener(
        (v, in) -> {
          var b = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
          v.setPadding(b.left, b.top, b.right, b.bottom);
          return in;
        });
    setContentView(root);
    getWindow()
        .getInsetsController()
        .setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    LinearLayout top = row();
    top.setPadding(dp(22), dp(14), dp(22), dp(12));
    TextView brand = text("食迹", 24, GREEN, true);
    top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    subtitle = text("本地记录 · 私人饮食日记", 11, MUTED, false);
    add(top, subtitle);
    add(root, top);
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    body = column();
    body.setPadding(dp(20), dp(12), dp(20), dp(22));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    nav = row();
    nav.setPadding(dp(12), dp(8), dp(12), dp(10));
    add(root, nav);
    render();
    Jobs.configure(this);
    handleIntent(getIntent());
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putInt("page", page);
    out.putString("date", date);
  }

  // The legacy branch is restricted to API 30–32, before exported flags existed.
  @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(
          changed, new IntentFilter("dev.mealtrace.CHANGED"), Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(changed, new IntentFilter("dev.mealtrace.CHANGED"));
    getContentResolver()
        .registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
    render();
    Jobs.configure(this);
    if (prefs.enabled()) handler.postDelayed(autoScan, 1000);
  }

  @Override
  protected void onPause() {
    resumed = false;
    unregisterReceiver(changed);
    getContentResolver().unregisterContentObserver(observer);
    handler.removeCallbacks(autoScan);
    super.onPause();
  }

  @Override
  public void onNewIntent(Intent i) {
    super.onNewIntent(i);
    setIntent(i);
    handleIntent(i);
  }

  void handleIntent(Intent i) {
    if (Intent.ACTION_SEND.equals(i.getAction())) {
      Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
      if (u != null) importPhoto(u);
      i.setAction(null);
    }
  }

  void render() {
    if (body == null) return;
    int y = scroll.getScrollY();
    body.removeAllViews();
    nav.removeAllViews();
    String[] tabs = {"◉  今天", "▤  餐食", "▥  趋势", "⚙  设置"};
    for (int i = 0; i < tabs.length; i++) {
      final int index = i;
      TextView b = text(tabs[i], 13, page == i ? GREEN : MUTED, page == i);
      b.setGravity(Gravity.CENTER);
      b.setPadding(0, dp(12), 0, dp(12));
      if (i == page) b.setBackground(bg(PALE, 14));
      b.setOnClickListener(
          v -> {
            page = index;
            scroll.scrollTo(0, 0);
            render();
          });
      nav.addView(b, new LinearLayout.LayoutParams(0, dp(46), 1));
    }
    if (page == 0) home();
    else if (page == 1) meals();
    else if (page == 2) trends();
    else settings();
    scroll.post(() -> scroll.scrollTo(0, y));
  }

  void pickDate() {
    LocalDate d = LocalDate.parse(date);
    new DatePickerDialog(
            this,
            (v, y, m, day) -> {
              date = LocalDate.of(y, m + 1, day).toString();
              render();
            },
            d.getYear(),
            d.getMonthValue() - 1,
            d.getDayOfMonth())
        .show();
  }

  void home() {
    LocalDate d = LocalDate.parse(date);
    heading(date.equals(today()) ? "好好吃饭，慢慢改变。" : date, "照片帮你记，份量由你确认。");
    TextView day =
        button(
            d.format(DateTimeFormatter.ofPattern("M 月 d 日 · E", Locale.CHINA)) + "  ▾",
            this::pickDate);
    add(body, day);
    gap(body, 14);
    Nutrition.Total total = store.total(date);
    Nutrition.Profile p = prefs.profile();
    double target = p.target();
    LinearLayout hero = card(body);
    LinearLayout hr = row();
    LinearLayout numbers = column();
    add(numbers, text("已记录摄入", 12, MUTED, false));
    add(numbers, text(Nutrition.round(total.kcal) + " kcal", 32, INK, true));
    add(
        numbers,
        text(
            total.records == 0
                ? "今天的故事从第一餐开始"
                : "估算范围 "
                    + Nutrition.round(total.low)
                    + "～"
                    + Nutrition.round(total.high)
                    + " kcal",
            12,
            MUTED,
            false));
    gap(numbers, 8);
    add(
        numbers,
        text(
            target > 0 ? "每日参考 " + Nutrition.round(target) + " kcal" : "去设置建立个人目标 →",
            12,
            GREEN,
            true));
    hr.addView(numbers, new LinearLayout.LayoutParams(0, -2, 1));
    hr.addView(new Ring(this, total.kcal, target), new LinearLayout.LayoutParams(dp(83), dp(83)));
    add(hero, hr);
    gap(hero, 18);
    actions(
        hero,
        new String[] {
          "蛋白质 " + Nutrition.round(total.protein) + "g",
          "碳水 " + Nutrition.round(total.carbs) + "g",
          "脂肪 " + Nutrition.round(total.fat) + "g"
        },
        new Runnable[] {() -> {}, () -> {}, () -> {}});
    Nutrition.Advice a =
        Nutrition.advice(
            p,
            total,
            prefs.complete(date, false),
            prefs.complete(date, true),
            date.equals(today()) ? LocalTime.now().getHour() : 23);
    LinearLayout advice = card(body);
    add(advice, text(a.title(), 17, GREEN, true));
    gap(advice, 7);
    add(advice, text(a.body(), 14, INK, false));
    actions(
        body,
        new String[] {"＋ 补记食物", "导入餐食照片"},
        new Runnable[] {() -> foodEditor(null), this::pickPhoto});
    gap(body, 18);
    LinearLayout coverage = card(body);
    add(coverage, text("记录完整度", 16, INK, true));
    gap(coverage, 6);
    add(coverage, text("截至目前的核对有效 2 小时；新增或修改食物后需重新核对。全天核对在 20 点后开放。", 12, MUTED, false));
    gap(coverage, 10);
    actions(
        coverage,
        new String[] {
          prefs.complete(date, false) ? "✓ 截至现在已记全" : "截至现在已记全",
          prefs.complete(date, true) ? "✓ 全天已记全" : "全天已经记全"
        },
        new Runnable[] {() -> coverage(false), () -> coverage(true)});
    var entries = store.day(date);
    if (entries.stream().noneMatch(Store.Meal::active)) {
      LinearLayout empty = card(body);
      add(empty, text("让相机成为你的饮食日记", 17, INK, true));
      gap(empty, 8);
      add(empty, text("在设置开启自动发现新照片，也可以直接从相册分享到食迹。饮料、零食和没有拍照的一餐可" + "随时补记。", 14, MUTED, false));
    } else {
      add(body, text("这一天的餐食", 18, INK, true));
      gap(body, 10);
      for (Store.Meal m : entries) if (m.active()) mealRow(body, m);
    }
  }

  void coverage(boolean day) {
    if (day && date.equals(today()) && LocalTime.now().getHour() < 20) {
      toast("当天 20 点后可确认全天；现在可先确认截至目前");
      return;
    }
    if (!day && !date.equals(today())) {
      toast("过去的日期请核对全天记录");
      return;
    }
    Nutrition.Total t = store.total(date);
    if (t.records == 0 || t.unconfirmed > 0 || t.unknown > 0) {
      dialog("先确认餐食", "请先补记并确认当天食物份量，再标记完整。没有照片不等于没有吃。", null);
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle(day ? "今天的食物都记全了吗？" : "截至现在都记全了吗？")
        .setMessage("请一并核对饮料、零食、油和酱料，以及没有拍照的餐食。此标记表示你已核对，不是应" + "用自动判断。")
        .setNegativeButton("继续核对", null)
        .setPositiveButton(
            "已核对",
            (v, w) -> {
              prefs.complete(date, day, true);
              render();
              MealWidget.update(this);
            })
        .show();
  }

  void meals() {
    heading("每一餐，都有迹可循。", "自动识别的结果会先标为待确认。");
    actions(
        body,
        new String[] {working ? "正在扫描…" : "扫描新照片", "选择照片"},
        new Runnable[] {() -> runScan(true), this::pickPhoto});
    gap(body, 14);
    if (store.recent().isEmpty()) {
      LinearLayout c = card(body);
      add(c, text("还没有餐食记录", 18, INK, true));
      gap(c, 8);
      add(c, text("先导入一张餐食照片，或在设置开启相机目录自动监测。", 14, MUTED, false));
    }
    for (Store.Meal m : store.recent()) mealRow(body, m);
  }

  Bitmap thumbnail(String path, int max) {
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(path, o);
    o.inSampleSize = 1;
    while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > max * 2) o.inSampleSize *= 2;
    o.inJustDecodeBounds = false;
    return BitmapFactory.decodeFile(path, o);
  }

  void mealRow(LinearLayout parent, Store.Meal m) {
    LinearLayout c = card(parent);
    LinearLayout r = row();
    if (!m.thumb.isEmpty()) {
      ImageView image = new ImageView(this);
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      Bitmap bitmap = thumbnail(m.thumb, 128);
      image.setImageBitmap(bitmap);
      image.setBackground(bg(PALE, 12));
      image.setClipToOutline(true);
      r.addView(image, new LinearLayout.LayoutParams(dp(62), dp(62)));
    }
    LinearLayout info = column();
    info.setPadding(dp(12), 0, 0, 0);
    add(info, text(m.title, 16, INK, true));
    add(info, text(m.day + " · " + Nutrition.SLOTS[m.slot] + " · " + m.state(), 11, MUTED, false));
    if (m.known()) add(info, text(Nutrition.round(m.n("kcal")) + " kcal", 15, GREEN, true));
    r.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
    add(c, r);
    c.setOnClickListener(v -> detail(m.id));
  }

  void detail(long id) {
    Store.Meal m = store.meal(id);
    if (m == null) return;
    LinearLayout c = column();
    pad(c, 20);
    if (!m.thumb.isEmpty()) {
      ImageView im = new ImageView(this);
      im.setScaleType(ImageView.ScaleType.CENTER_CROP);
      im.setImageBitmap(thumbnail(m.thumb, 640));
      c.addView(im, new LinearLayout.LayoutParams(-1, dp(170)));
      gap(c, 12);
    }
    add(c, text(m.title, 22, INK, true));
    add(c, text(m.day + " · " + Nutrition.SLOTS[m.slot] + " · " + m.state(), 12, MUTED, false));
    gap(c, 10);
    if (m.known()) {
      add(
          c,
          text(
              Nutrition.round(m.n("kcal"))
                  + " kcal · "
                  + Nutrition.round(m.n("low"))
                  + "～"
                  + Nutrition.round(m.n("high")),
              20,
              GREEN,
              true));
      gap(c, 8);
    }
    JSONArray items = m.data.optJSONArray("items");
    if (items != null)
      for (int i = 0; i < items.length(); i++) {
        JSONObject f = items.optJSONObject(i);
        if (f != null)
          add(
              c,
              text(
                  f.optString("name")
                      + "  "
                      + Nutrition.round(f.optDouble("grams") * m.fraction)
                      + "g",
                  13,
                  INK,
                  false));
      }
    if (!m.error.isEmpty()) add(c, text(m.error, 13, Color.rgb(146, 95, 49), false));
    gap(c, 8);
    add(
        c,
        text(
            m.data.optString("method", "本地识别") + "。视觉份量可能偏差较大，范围不是统计置信区间；请核对用油、多人分餐和剩饭。",
            12,
            MUTED,
            false));
    gap(c, 14);
    AlertDialog a =
        new AlertDialog.Builder(this).setView(wrap(c)).setNegativeButton("关闭", null).create();
    actions(
        c,
        new String[] {"我吃完了", "我吃了一半", "修改食材"},
        new Runnable[] {
          () -> {
            confirm(m, 1);
            a.dismiss();
          },
          () -> {
            confirm(m, .5);
            a.dismiss();
          },
          () -> {
            a.dismiss();
            foodEditor(m);
          }
        });
    gap(c, 10);
    actions(
        c,
        new String[] {"自定义分餐比例", "调整日期 / 餐次"},
        new Runnable[] {
          () -> {
            a.dismiss();
            fraction(m);
          },
          () -> {
            a.dismiss();
            time(m);
          }
        });
    gap(c, 10);
    CheckBox favorite = new CheckBox(this);
    favorite.setText("收藏保留这张照片");
    favorite.setChecked(m.favorite);
    favorite.setOnCheckedChangeListener(
        (v, on) -> {
          ContentValues val = new ContentValues();
          val.put("favorite", on ? 1 : 0);
          store.update(m.id, val);
        });
    add(c, favorite);
    CheckBox clean = new CheckBox(this);
    clean.setText("已确认后，允许月度清理此原图");
    clean.setChecked(m.cleanup);
    clean.setOnCheckedChangeListener(
        (v, on) -> {
          ContentValues val = new ContentValues();
          val.put("cleanup", on ? 1 : 0);
          store.update(m.id, val);
        });
    add(c, clean);
    gap(c, 8);
    actions(
        c,
        new String[] {"不是我的餐食", "重复照片", "重新识别"},
        new Runnable[] {
          () -> {
            state(m, "ignored");
            a.dismiss();
          },
          () -> {
            state(m, "duplicate");
            a.dismiss();
          },
          () -> {
            ContentValues v = new ContentValues();
            v.put("status", "pending");
            v.put("confirmed", 0);
            v.put("attempts", 0);
            v.put("nexttry", 0);
            store.update(m.id, v);
            prefs.invalidate(m.day);
            a.dismiss();
            runScan(true);
          }
        });
    a.show();
  }

  void confirm(Store.Meal m, double f) {
    if (!m.known() || m.data.optBoolean("unknown")) {
      toast("请先修正未识别或未匹配的食物");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("食材、用油与份量都核对了吗？")
        .setMessage("本地模型可能漏掉食物或猜错重量。请确认这份记录已包含实际吃下的食材、饮料和调料，再记录食用比例。需要补充时先修改食材。")
        .setNegativeButton("继续修改", (d, w) -> foodEditor(m))
        .setPositiveButton(
            "已核对，确认记录",
            (d, w) -> {
              ContentValues v = new ContentValues();
              v.put("confirmed", 1);
              v.put("fraction", f);
              store.update(m.id, v);
              prefs.invalidate(m.day);
              MealApp.changed(this);
              render();
            })
        .show();
  }

  void state(Store.Meal m, String state) {
    ContentValues v = new ContentValues();
    v.put("status", state);
    v.put("confirmed", 0);
    store.update(m.id, v);
    prefs.invalidate(m.day);
    MealApp.changed(this);
    render();
  }

  void fraction(Store.Meal m) {
    EditText e = field("例如 0.3 代表吃了整份的 30%", "", true);
    new AlertDialog.Builder(this)
        .setTitle("实际吃掉的比例")
        .setView(e)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "确认",
            (d, w) -> {
              try {
                double f = Double.parseDouble(e.getText().toString());
                if (!Double.isFinite(f) || f <= 0 || f > 1) throw new Exception();
                confirm(m, f);
              } catch (Exception ex) {
                toast("请输入大于 0 且不超过 1 的比例");
              }
            })
        .show();
  }

  void time(Store.Meal m) {
    LocalDate d = LocalDate.parse(m.day);
    new DatePickerDialog(
            this,
            (view, y, month, day) -> {
              String dayString = LocalDate.of(y, month + 1, day).toString();
              new AlertDialog.Builder(this)
                  .setTitle("选择餐次")
                  .setItems(
                      Nutrition.SLOTS,
                      (di, slot) -> {
                        ContentValues v = new ContentValues();
                        v.put("day", dayString);
                        v.put("slot", slot);
                        v.put(
                            "ts",
                            LocalDate.parse(dayString)
                                .atTime(new int[] {8, 12, 19, 16}[slot], 0)
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli());
                        store.update(m.id, v);
                        prefs.invalidate(m.day);
                        prefs.invalidate(dayString);
                        MealApp.changed(this);
                        render();
                      })
                  .show();
            },
            d.getYear(),
            d.getMonthValue() - 1,
            d.getDayOfMonth())
        .show();
  }

  EditText field(String hint, String value, boolean numeric) {
    EditText e = new EditText(this);
    e.setTextSize(15);
    e.setText(value);
    e.setHint(hint);
    e.setSingleLine();
    e.setPadding(dp(6), dp(8), dp(6), dp(8));
    if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    return e;
  }

  ScrollView wrap(View v) {
    ScrollView s = new ScrollView(this);
    s.addView(v);
    return s;
  }

  void foodEditor(Store.Meal existing) {
    try {
      Foods catalog = new Foods(this);
      LinearLayout c = column();
      pad(c, 20);
      EditText name = field("这餐叫什么？", existing == null ? "" : existing.title, false);
      add(c, name);
      gap(c, 10);
      add(c, text("食材可食部分的克数；额外的烹调油也要加入。", 12, MUTED, false));
      gap(c, 10);
      LinearLayout list = column();
      add(c, list);
      ArrayList<FoodInput> inputs = new ArrayList<>();
      CheckBox unknownAcknowledged = new CheckBox(this);
      boolean hasUnknown = existing != null && existing.data.optBoolean("unknown");
      if (hasUnknown) {
        unknownAcknowledged.setText("我已补齐未匹配的食物，或确认这些项目不应计入摄入");
        add(c, unknownAcknowledged);
      }
      JSONArray current = existing == null ? null : existing.data.optJSONArray("items");
      if (current != null)
        for (int i = 0; i < current.length(); i++) {
          JSONObject f = current.getJSONObject(i);
          if (catalog.foods.containsKey(f.optString("id")))
            foodInput(
                list, inputs, catalog, f.getString("id"), f.optDouble("grams") * existing.fraction);
        }
      TextView addFood =
          button(
              "＋ 选择食材",
              () -> {
                chooseFood(catalog, id -> foodInput(list, inputs, catalog, id,
                    catalog.foods.get(id).optDouble("serving")));
              });
      add(c, addFood);
      gap(c, 10);
      TextView note =
          text("营养数据：USDA SR " + "Legacy；中式菜通过食材近似。生熟状态、配方与品牌差异仍会影响结果。", 11, MUTED, false);
      add(c, note);
      AlertDialog a =
          new AlertDialog.Builder(this)
              .setTitle(existing == null ? "补记一餐" : "修正这餐")
              .setView(wrap(c))
              .setNegativeButton("取消", null)
              .setPositiveButton("保存", null)
              .create();
      a.setOnShowListener(
          x ->
              a.getButton(-1)
                  .setOnClickListener(
                      v -> {
                        try {
                          if (hasUnknown && !unknownAcknowledged.isChecked())
                            throw new IllegalArgumentException("请先补齐并核对未匹配的食物");
                          if (inputs.isEmpty()) throw new IllegalArgumentException("请至少添加一种食材");
                          JSONArray items = new JSONArray();
                          for (FoodInput f : inputs)
                            items.put(
                                new JSONObject()
                                    .put("id", f.id)
                                    .put(
                                        "grams", Double.parseDouble(f.grams.getText().toString())));
                          JSONObject raw =
                              new JSONObject()
                                  .put("food", true)
                                  .put(
                                      "name",
                                      name.getText().toString().isBlank()
                                          ? "手动记录"
                                          : name.getText().toString())
                                  .put("items", items)
                                  .put("oil", 0);
                          JSONObject result = catalog.calculate(raw, true);
                          long id;
                          if (existing == null) {
                            long ts =
                                LocalDate.parse(date)
                                    .atTime(LocalTime.now())
                                    .atZone(ZoneId.systemDefault())
                                    .toInstant()
                                    .toEpochMilli();
                            id = store.add("manual:" + UUID.randomUUID(), ts, "", "");
                          } else id = existing.id;
                          ContentValues val = new ContentValues();
                          val.put("title", result.getString("name"));
                          val.put("payload", result.toString());
                          val.put("status", "manual");
                          val.put("confirmed", 1);
                          val.put("fraction", 1);
                          val.put("error", "");
                          store.update(id, val);
                          prefs.invalidate(existing == null ? date : existing.day);
                          MealApp.changed(this);
                          a.dismiss();
                          render();
                        } catch (Exception ex) {
                          toast(ex.getMessage() == null ? "请检查食材份量" : ex.getMessage());
                        }
                      }));
      a.show();
    } catch (Exception e) {
      toast("无法读取本地食物表");
    }
  }

  void chooseFood(Foods catalog, java.util.function.Consumer<String> choose) {
      LinearLayout c = column();
      pad(c, 16);
      EditText search = field("名称、中文别名或英文关键词", "", false);
      add(c, search);
      Spinner category = new Spinner(this);
      category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
          new String[] {"全部", "水果", "肉类", "蛋类", "奶类", "水产", "主食", "蔬菜", "豆类",
              "坚果种子", "饮品", "调味", "油脂", "自定义"}));
      add(c, category);
      CheckBox all = new CheckBox(this);
      all.setText("包含 USDA 全部条目（英文原名）");
      add(c, all);
      TextView count = text("", 12, MUTED, false);
      add(c, count);
      ListView list = new ListView(this);
      c.addView(list, new LinearLayout.LayoutParams(-1, dp(320)));
      ArrayList<String> ids = new ArrayList<>();
      ArrayAdapter<String> adapter =
          new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
      list.setAdapter(adapter);
      AlertDialog dialog = new AlertDialog.Builder(this)
                               .setTitle("选择食材")
                               .setView(c)
                               .setNegativeButton("关闭", null)
                               .create();
      Runnable refresh = () -> {
        List<String> result = catalog.search(
            search.getText().toString(), category.getSelectedItem().toString(), all.isChecked());
        ids.clear();
        ids.addAll(result.subList(0, Math.min(150, result.size())));
        adapter.clear();
        for (String id : ids) {
          JSONObject f = catalog.foods.get(id);
          adapter.add(f.optString("name") + "\n每100克 " + Nutrition.round(f.optDouble("kcal"))
              + " kcal · " + f.optString("category") + " · "
              + f.optString("source", "USDA 食材参考"));
        }
        count.setText(result.size() + " 个匹配，显示前150个；更多可缩小搜索范围");
        adapter.notifyDataSetChanged();
      };
      search.addTextChangedListener(new android.text.TextWatcher() {
        public void beforeTextChanged(CharSequence t, int start, int count, int after) {}
        public void onTextChanged(CharSequence t, int start, int before, int count) {
          refresh.run();
        }
        public void afterTextChanged(android.text.Editable t) {}
      });
      category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
          refresh.run();
        }
        public void onNothingSelected(AdapterView<?> parent) {}
      });
      all.setOnCheckedChangeListener((v, checked) -> refresh.run());
      list.setOnItemClickListener((parent, view, position, id) -> {
        choose.accept(ids.get(position));
        dialog.dismiss();
      });
      add(c,
          button(
              "＋ 其他／自定义食物", () -> customFood(catalog, search.getText().toString(), id -> {
                choose.accept(id);
                dialog.dismiss();
              })));
      add(c,
          text("未匹配食物可以按包装营养表添加；选近似食材时请核对生熟、配方与品牌。", 11, MUTED,
              false));
      dialog.show();
      refresh.run();
    }

    void customFood(Foods catalog, String initial, java.util.function.Consumer<String> choose) {
      LinearLayout c = column();
      pad(c, 20);
      add(c, text("全部按每100克填写；按100毫升标注时请先用实际密度换算。", 12, MUTED, false));
      EditText name = field("品牌、名称或自制配方", initial, false);
      add(c, name);
      Spinner unit = new Spinner(this);
      unit.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
          new String[] {"能量单位：kcal 千卡", "能量单位：kJ 千焦"}));
      add(c, unit);
      EditText energy = field("每100克能量（必填）", "", true);
      add(c, energy);
      EditText protein = field("每100克蛋白质 g（必填，可为0）", "", true);
      add(c, protein);
      EditText carbs = field("每100克碳水化合物 g（必填，可为0）", "", true);
      add(c, carbs);
      EditText fat = field("每100克脂肪 g（必填，可为0）", "", true);
      add(c, fat);
      add(c, text("默认一份克数", 12, MUTED, false));
      EditText serving = field("一份克数", "100", true);
      add(c, serving);
      add(c,
          text("从包装、可靠数据或已知配方填写，不能把整包数据直接当作每100克。保存后标记为用户填写"
               + "，供以后复用。",
              11, MUTED, false));
      AlertDialog dialog = new AlertDialog.Builder(this)
                               .setTitle("自定义食物")
                               .setView(wrap(c))
                               .setNegativeButton("取消", null)
                               .setPositiveButton("保存并选用", null)
                               .create();
      dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(button -> {
        try {
          double e = Double.parseDouble(energy.getText().toString());
          if (unit.getSelectedItemPosition() == 1)
            e /= 4.184;
          JSONObject f = catalog.saveCustom(name.getText().toString(), e,
              Double.parseDouble(protein.getText().toString()),
              Double.parseDouble(carbs.getText().toString()),
              Double.parseDouble(fat.getText().toString()),
              Double.parseDouble(serving.getText().toString()));
          choose.accept(f.getString("id"));
          dialog.dismiss();
        } catch (Exception ex) {
          toast(ex.getMessage() == null ? "请填写完整的每100克营养数据" : ex.getMessage());
        }
      }));
      dialog.show();
    }

    static class FoodInput {
    String id;
    EditText grams;

    FoodInput(String id, EditText g) {
      this.id = id;
      grams = g;
    }
  }

  void foodInput(
      LinearLayout list, ArrayList<FoodInput> inputs, Foods foods, String id, double grams) {
    LinearLayout r = row();
    TextView label = text(foods.foods.get(id).optString("name"), 14, INK, false);
    r.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    EditText g = field("克", Nutrition.round(grams), true);
    r.addView(g, new LinearLayout.LayoutParams(dp(70), -2));
    add(r, text("g", 12, MUTED, false));
    FoodInput f = new FoodInput(id, g);
    inputs.add(f);
    TextView remove =
        button(
            "×",
            () -> {
              inputs.remove(f);
              list.removeView(r);
            });
    add(r, remove);
    add(list, r);
  }

  void pickPhoto() {
    Intent i =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE);
    startActivityForResult(i, 10);
  }

  void importPhoto(Uri u) {
    working = true;
    toast("正在添加照片…");
    MealApp.WORK.submit(
        () -> {
          try {
            Scanner.importPhoto(this, u);
            Scanner.analyze(this, new AtomicBoolean());
          } catch (Exception e) {
            handler.post(() -> toast("照片未能导入，请重新选择"));
          } finally {
            working = false;
            handler.post(
                () -> {
                  page = 1;
                  render();
                });
          }
        });
  }

  void runScan(boolean tell) {
    if (working) {
      if (tell) toast("正在处理上一批照片");
      return;
    }
    working = true;
    render();
    MealApp.WORK.submit(
        () -> {
          try {
            Scanner.scan(this, new AtomicBoolean());
            Scanner.analyze(this, new AtomicBoolean());
            Cleanup.remind(this);
            if (tell) handler.post(() -> toast("本轮扫描完成"));
          } catch (Exception e) {
            if (tell) handler.post(() -> toast("扫描尚未完成，检查照片权限与模型状态"));
          } finally {
            working = false;
            MealApp.changed(this);
            handler.post(this::render);
          }
        });
  }

  void trends() {
    heading("看趋势，少一点焦虑。", "未记录的日子不会被算成零摄入。");
    actions(
        body,
        new String[] {
          range == 7 ? "✓ 近 7 天" : "近 7 天", range == 30 ? "✓ 近 30 天" : "近 30 天", "记录体重"
        },
        new Runnable[] {
          () -> {
            range = 7;
            render();
          },
          () -> {
            range = 30;
            render();
          },
          this::weight
        });
    gap(body, 14);
    double sum = 0;
    int days = 0, complete = 0;
    ArrayList<Nutrition.Total> totals = new ArrayList<>();
    for (int i = range - 1; i >= 0; i--) {
      String d = LocalDate.now().minusDays(i).toString();
      Nutrition.Total t = store.total(d);
      totals.add(t);
      if (t.records > 0) {
        days++;
        sum += t.kcal;
      }
      if (prefs.complete(d, true) && t.unknown == 0 && t.unconfirmed == 0) complete++;
    }
    LinearLayout c = card(body);
    add(c, text("记录了 " + days + " / " + range + " 天", 23, INK, true));
    gap(c, 7);
    add(
        c,
        text(
            "已记录食物合计 " + Nutrition.round(sum) + " kcal · 全天确认 " + complete + " 天",
            13,
            MUTED,
            false));
    gap(c, 14);
    c.addView(new Bars(this, totals), new LinearLayout.LayoutParams(-1, dp(130)));
    gap(c, 12);
    add(c, text("图表展示已记录能量；浅色空位表示缺失，不表示没吃饭。", 12, MUTED, false));
    LinearLayout insight = card(body);
    add(insight, text("这一阶段可以关注", 17, GREEN, true));
    gap(insight, 8);
    add(
        insight,
        text(
            complete < Math.max(3, range / 2)
                ? "先提高完整记录的天数。餐食不全时，无法可靠判断热量缺口或是否吃够；尤其别漏记" + "饮料与零食。"
                : "观察连续数周的体重和饥饿感，而非用单日照片决定少吃。保持正餐规律，注意食物种" + "类；能量接近目标不等于营养充分。",
            14,
            INK,
            false));
    try {
      JSONArray weights = store.weights();
      if (weights.length() > 0) {
        LinearLayout w = card(body);
        add(w, text("体重记录", 17, INK, true));
        for (int i = 0; i < Math.min(10, weights.length()); i++) {
          JSONObject v = weights.getJSONObject(i);
          add(w, text(v.getString("day") + "   " + v.getDouble("kg") + " kg", 14, MUTED, false));
        }
      }
    } catch (Exception ignored) {
    }
    add(
        body,
        button(
            "导出饮食与体重记录 JSON",
            () ->
                startActivityForResult(
                    new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .setType("application/json")
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_TITLE, "MealTrace-" + today() + ".json"),
                    20)));
  }

  void weight() {
    EditText e = field("当前体重，kg", "", true);
    AlertDialog a =
        new AlertDialog.Builder(this)
            .setTitle("记录今天的体重")
            .setView(e)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create();
    a.setOnShowListener(
        v ->
            a.getButton(-1)
                .setOnClickListener(
                    w -> {
                      try {
                        double n = Double.parseDouble(e.getText().toString());
                        if (!Double.isFinite(n) || n < 35 || n > 250) throw new Exception();
                        store.weight(today(), n);
                        a.dismiss();
                        render();
                      } catch (Exception x) {
                        toast("请输入 35～250 kg 之间的数值");
                      }
                    }));
    a.show();
  }

  void settings() {
    heading("为你而设。", "离线识别，不需要账号或服务密钥。");
    LinearLayout model = card(body);
    boolean ready = ModelFiles.ready(this);
    add(model, text(ready ? "✓ 本地模型已就绪" : "准备本地视觉模型", 18, GREEN, true));
    gap(model, 7);
    add(
        model,
        text(
            ModelDownload.running
                ? ModelDownload.progress
                : ready
                    ? "Qwen3.5-2B · 离线识别\n" + new Acceleration(this).description()
                    : "首次需约 1.95 GB " + "模型文件。下载只获取公开模型，不上传照片；之后可断网使用。",
            13,
            MUTED,
            false));
    gap(model, 12);
    Acceleration acceleration = new Acceleration(this);
    Switch npu = new Switch(this);
    npu.setText("优先使用 NPU 加速");
    npu.setChecked(acceleration.enabled());
    npu.setOnCheckedChangeListener((v, on) -> {
      acceleration.enabled(on);
      render();
    });
    add(model, npu);
    if (!ready)
      add(
          model,
          button(
              ModelDownload.running ? "正在下载…" : "下载离线模型",
              () -> {
                if (!ModelDownload.running)
                  startForegroundService(new Intent(this, ModelDownload.class));
              }));
    add(
        model,
        button(
            "从本地模型包导入",
            () ->
                startActivityForResult(
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("application/zip")
                        .addCategory(Intent.CATEGORY_OPENABLE),
                    11)));
    add(
        model,
        button(
            "校验已导入的本地模型",
            () -> {
              toast("正在校验…");
              MealApp.WORK.submit(
                  () -> {
                    try {
                      ModelFiles.verify(this);
                      handler.post(() -> toast("模型校验通过"));
                    } catch (Exception e) {
                      handler.post(() -> toast("模型不完整，可下载或由 USB 导入"));
                    } finally {
                      handler.post(this::render);
                    }
                  });
            }));
    LinearLayout auto = card(body);
    add(auto, text("相机照片自动记账", 18, INK, true));
    Switch watch = new Switch(this);
    watch.setText("自动发现新餐食照片");
    watch.setChecked(prefs.enabled());
    watch.setOnCheckedChangeListener(
        (v, on) -> {
          if (on && !Scanner.fullPermission(this)) {
            requestPhotos();
            v.setChecked(false);
            return;
          }
          prefs.watch(on);
          Jobs.configure(this);
          if (on) runScan(true);
        });
    add(auto, watch);
    gap(auto, 6);
    add(
        auto,
        text(
            "开启后只检查新加入 DCIM/Camera " + "的照片。本地筛选后识别，开机恢复监测，并定期补扫。系统省电可能延后处理；强制" + "停止后需重新打开。",
            12,
            MUTED,
            false));
    gap(auto, 10);
    add(
        auto,
        text(
            "照片访问：" + (Scanner.fullPermission(this) ? "完整照片访问已开启" : "未获完整访问，可手动导入"),
            13,
            INK,
            false));
    add(auto, button("设置照片与通知权限", this::requestPhotos));
    gap(auto, 8);
    long last = prefs.p.getLong("last_scan", 0);
    add(
        auto,
        text(
            last == 0
                ? "尚未执行后台扫描"
                : "最近扫描 "
                    + Instant.ofEpochMilli(last)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("M-d HH:mm"))
                    + " · "
                    + prefs.p.getString("scan_status", ""),
            11,
            MUTED,
            false));
    actions(
        auto,
        new String[] {"小米自启动设置", "应用后台与省电"},
        new Runnable[] {
          this::autostart,
          () ->
              open(
                  new Intent(
                      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                      Uri.parse("package:" + getPackageName())))
        });
    LinearLayout goal = card(body);
    Nutrition.Profile p = prefs.profile();
    add(goal, text("个人能量参考", 18, INK, true));
    gap(goal, 7);
    add(
        goal,
        text(
            p.target() > 0
                ? "每日参考 "
                    + Nutrition.round(p.target())
                    + " kcal · "
                    + (p.manualTarget > 0 ? "自定目标" : "静息代谢公式与活动估计")
                : "填写资料后，才提供个人加餐参考。",
            14,
            MUTED,
            false));
    gap(goal, 10);
    add(goal, button("编辑身高、体重与减重目标", this::profile));
    LinearLayout clean = card(body);
    add(clean, text("月度照片整理", 18, INK, true));
    gap(clean, 7);
    add(
        clean,
        text("只列出超过 30 " + "天、已确认且你允许清理的原图；收藏照片会保留。移入回收站后，缩略图和营养记录" + "继续保留。", 13, MUTED, false));
    gap(clean, 10);
    add(clean, button("查看可清理照片（" + Cleanup.candidates(this).size() + "）", this::cleanup));
    gap(clean, 6);
    add(clean, text("第一版每月通知后批量确认清理。回收站清空后才释放空间；云相册是否同步删除由相册" + "设置决定。", 11, MUTED, false));
    LinearLayout about = card(body);
    add(about, text("食迹 0.1 · 离线试用版", 16, INK, true));
    gap(about, 8);
    add(
        about,
        text(
            "为日常饮食记录与一般减重习惯设计。照片无法测量所有食物、用油和实际摄入。建议是" + "记录辅助，不能判断营养是否充足或替代个人医疗方案。",
            12,
            MUTED,
            false));
    gap(about, 8);
    add(about, button("阅读调研、计算依据与隐私说明", this::about));
  }

  void requestPhotos() {
    new AlertDialog.Builder(this)
        .setTitle("自动发现新照片需要照片访问")
        .setMessage("食迹只在本机筛选相机目录的新照片，不上传。请选择允许访问所有照片，才能发" + "现以后拍摄的食物；也可只导入选定照片。")
        .setNegativeButton("手动导入", (v, w) -> pickPhoto())
        .setPositiveButton(
            "授予权限",
            (v, w) -> {
              ArrayList<String> a = new ArrayList<>();
              if (Build.VERSION.SDK_INT >= 33) {
                a.add(Manifest.permission.READ_MEDIA_IMAGES);
                if (Build.VERSION.SDK_INT >= 34)
                  a.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
                a.add(Manifest.permission.POST_NOTIFICATIONS);
              } else a.add(Manifest.permission.READ_EXTERNAL_STORAGE);
              requestPermissions(a.toArray(new String[0]), 1);
            })
        .show();
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] ps, int[] rs) {
    super.onRequestPermissionsResult(code, ps, rs);
    if (code == 1) {
      if (Scanner.fullPermission(this)) {
        prefs.watch(true);
        Jobs.configure(this);
        runScan(true);
      } else toast("可继续手动导入照片，自动监测需要完整照片权限");
      render();
    }
  }

  void autostart() {
    Intent i = new Intent();
    i.setComponent(
        new ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"));
    try {
      startActivity(i);
    } catch (Exception e) {
      dialog("小米后台自启动", "在系统设置中搜索“自启动”，允许食迹。应用信息中可检查省电限制。菜单位置依系统版本不" + "同。", null);
    }
  }

  void open(Intent i) {
    try {
      startActivity(i);
    } catch (Exception e) {
      toast("请从系统设置手动打开");
    }
  }

  void profile() {
    Nutrition.Profile old = prefs.profile();
    LinearLayout c = column();
    pad(c, 20);
    add(c, text("用于能量估算的成年期资料", 15, INK, true));
    EditText age = field("年龄", old.age == 0 ? "" : "" + old.age, true),
        height = field("身高 cm", old.height == 0 ? "" : Nutrition.round(old.height), true),
        weight = field("体重 kg", old.weight == 0 ? "" : "" + old.weight, true),
        target =
            field(
                "自定每日目标，可留空", old.manualTarget == 0 ? "" : Nutrition.round(old.manualTarget), true);
    add(c, age);
    add(c, height);
    add(c, weight);
    Spinner sex = new Spinner(this);
    sex.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"公式参数：男性", "公式参数：女性"}));
    sex.setSelection(old.male ? 0 : 1);
    add(c, sex);
    Spinner activity = new Spinner(this);
    activity.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"日常活动较少 ×1.2", "日常活动轻度 ×1.375", "日常活动中等 ×1.55"}));
    activity.setSelection(old.activity > 1.5 ? 2 : old.activity > 1.3 ? 1 : 0);
    add(c, activity);
    add(c, target);
    CheckBox special = new CheckBox(this);
    special.setText("孕期 / 哺乳期，或有需个体指导的疾病、用药、饮食问题");
    special.setChecked(old.special);
    add(c, special);
    gap(c, 8);
    add(
        c,
        text(
            "默认用 Mifflin–St Jeor 公式估计静息能量，再乘自选活动系数。BMI ≥24 时暂减 250 "
                + "kcal；正常范围优先维持。1400 kcal "
                + "是本产品保守限制，不是所有人的安全下限。未成年人和特殊情况不自动提供减重额度"
                + "。",
            12,
            MUTED,
            false));
    AlertDialog a =
        new AlertDialog.Builder(this)
            .setTitle("个人资料")
            .setView(wrap(c))
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create();
    a.setOnShowListener(
        v ->
            a.getButton(-1)
                .setOnClickListener(
                    w -> {
                      try {
                        Nutrition.Profile p = new Nutrition.Profile();
                        p.age = Integer.parseInt(age.getText().toString());
                        p.height = Double.parseDouble(height.getText().toString());
                        p.weight = Double.parseDouble(weight.getText().toString());
                        p.male = sex.getSelectedItemPosition() == 0;
                        p.activity =
                            new double[] {1.2, 1.375, 1.55}[activity.getSelectedItemPosition()];
                        p.special = special.isChecked();
                        String t = target.getText().toString();
                        p.manualTarget = t.isBlank() ? 0 : Double.parseDouble(t);
                        if (!p.ready())
                          throw new IllegalArgumentException("当前版本仅提供 18～85 岁成人资料估算，请检查身高、体重与年龄。");
                        if (p.manualTarget != 0 && (p.manualTarget < 1400 || p.manualTarget > 4500))
                          throw new IllegalArgumentException(
                              "此版本自定目标范围为 1400～4500 kcal；个体方案可先使用普通记账。");
                        prefs.profile(p);
                        prefs
                            .p
                            .edit()
                            .putLong("profile_updated", System.currentTimeMillis())
                            .apply();
                        MealWidget.update(this);
                        a.dismiss();
                        render();
                      } catch (Exception e) {
                        toast(e.getMessage() == null ? "请检查资料数值" : e.getMessage());
                      }
                    }));
    a.show();
  }

  void cleanup() {
    var list = Cleanup.candidates(this);
    if (list.isEmpty()) {
      dialog("暂无可清理原图", "需要记录超过 30 " + "天、已确认摄入，且你在餐食详情允许清理。收藏、手动导入和未确认照片会保留。", null);
      return;
    }
    StringBuilder names = new StringBuilder();
    for (int i = 0; i < Math.min(12, list.size()); i++)
      names.append(list.get(i).day).append(" · ").append(list.get(i).title).append('\n');
    new AlertDialog.Builder(this)
        .setTitle("将 " + list.size() + " 张原图移入回收站？")
        .setMessage(names + "\n缩略图和营养记录保留。回收站清空后才释放空间；云相册可能同步此操作。")
        .setNegativeButton("保留", null)
        .setPositiveButton(
            "移入回收站",
            (v, w) -> {
              try {
                trashRequest = list;
                startIntentSenderForResult(
                    Cleanup.request(this, list).getIntentSender(), 30, null, 0, 0, 0);
              } catch (Exception e) {
                toast("系统暂未允许清理，可从相册手动整理");
              }
            })
        .show();
  }

  void about() {
    try (var in = getAssets().open("about.txt")) {
      dialog("依据与隐私", new String(Io.read(in), StandardCharsets.UTF_8), null);
    } catch (Exception e) {
      toast("营养数据来自 USDA；推理模型 Qwen3.5；详细调研随源码提供。");
    }
  }

  void dialog(String title, String message, Runnable yes) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(
            "知道了",
            (v, w) -> {
              if (yes != null) yes.run();
            })
        .show();
  }

  void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 11 && result == RESULT_OK && data != null && data.getData() != null) {
      Uri u = data.getData();
      toast("正在导入并校验模型，请保持应用打开…");
      MealApp.WORK.submit(
          () -> {
            try {
              ModelFiles.importZip(this, u);
              handler.post(() -> toast("本地模型已准备好"));
            } catch (Exception e) {
              handler.post(() -> toast(e.getMessage() == null ? "模型包未导入" : e.getMessage()));
            } finally {
              handler.post(this::render);
            }
          });
    }
    if (request == 10 && result == RESULT_OK && data != null && data.getData() != null)
      importPhoto(data.getData());
    if (request == 20 && result == RESULT_OK && data != null && data.getData() != null) {
      Uri uri = data.getData();
      MealApp.WORK.submit(
          () -> {
            try (var out = getContentResolver().openOutputStream(uri)) {
              out.write(store.export(prefs).toString(2).getBytes(StandardCharsets.UTF_8));
              handler.post(() -> toast("记录已导出，不包含照片"));
            } catch (Exception e) {
              handler.post(() -> toast("导出未完成，请重试"));
            }
          });
    }
    if (request == 30) {
      if (result == RESULT_OK) Cleanup.reconcile(this, trashRequest);
      trashRequest = List.of();
      render();
    }
  }

  static final class Ring extends View {
    final Paint p = new Paint(3);
    final double value, target;

    Ring(Context c, double value, double target) {
      super(c);
      this.value = value;
      this.target = target;
      setContentDescription("已记录能量与参考目标的比例");
    }

    @Override
    protected void onDraw(Canvas c) {
      float w = getWidth(), h = getHeight(), stroke = w * .1f;
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(stroke);
      p.setColor(PALE);
      RectF r = new RectF(stroke, stroke, w - stroke, h - stroke);
      c.drawArc(r, 0, 360, false, p);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setColor(GREEN);
      c.drawArc(r, -90, target > 0 ? (float) Math.min(360, value / target * 360) : 0, false, p);
      p.setStyle(Paint.Style.FILL);
      p.setTextAlign(Paint.Align.CENTER);
      p.setTypeface(Typeface.DEFAULT_BOLD);
      p.setTextSize(w * .18f);
      c.drawText(target > 0 ? Math.round(value / target * 100) + "%" : "—", w / 2, h * .56f, p);
    }
  }

  static final class Bars extends View {
    final java.util.List<Nutrition.Total> values;
    final Paint p = new Paint(3);

    Bars(Context c, java.util.List<Nutrition.Total> v) {
      super(c);
      values = v;
      setContentDescription("已记录能量趋势图，缺失日期不计作零摄入");
    }

    @Override
    protected void onDraw(Canvas c) {
      double max = 1;
      for (var t : values) max = Math.max(max, t.kcal);
      float space = (float) getWidth() / values.size();
      for (int i = 0; i < values.size(); i++) {
        var t = values.get(i);
        p.setColor(PALE);
        c.drawRoundRect(
            i * space + space * .15f,
            0,
            (i + 1) * space - space * .15f,
            getHeight(),
            space * .2f,
            space * .2f,
            p);
        if (t.records > 0 && t.kcal > 0) {
          p.setColor(t.unconfirmed > 0 ? Color.rgb(171, 185, 142) : GREEN);
          float height = (float) (t.kcal / max * (getHeight() - 8));
          c.drawRoundRect(
              i * space + space * .15f,
              getHeight() - height,
              (i + 1) * space - space * .15f,
              getHeight(),
              space * .2f,
              space * .2f,
              p);
        }
      }
    }
  }
}
