# LyMeowz AI 🌸 — bản 2.0 (Minecraft 26.3)

> *"Hihi, mình định đi săn, sắp hết đồ ăn rồi nè~"*

Mod **Fabric cho Minecraft 26.3** thêm **LyMeowz**, một cô bạn đồng hành biết **sinh tồn như người chơi thật**.
Cô ấy có túi đồ, biết chặt cây, đào đá, chế tạo, đi săn, nướng thịt và tự ăn khi đói. Cô ấy có **tính cách, cảm xúc
và trí nhớ** riêng, tự quyết định nên làm gì, và tự ghép câu để nói chuyện mà không cần Internet.
Câu cửa miệng: **"Mugyu~"**, khi không hiểu hoặc khi ngại ngùng.

## Sinh tồn như người chơi

- **Túi đồ thật (27 ô).** Shift + chuột phải tay không vào cô ấy để mở túi; chuột phải khi cầm đồ để tặng đồ.
- **Đào và chặt như người chơi:** phải đứng trong tầm với và vung tay, block nứt dần theo đúng tốc độ của dụng cụ.
  Đồ rơi vào túi, dụng cụ bị mòn rồi gãy. Lúc chặt cây cô ấy cầm rìu, lúc đào đá thì cầm cuốc.
- **Tiến trình công nghệ:** gỗ → ván → que → bàn chế tạo → đồ gỗ → đá cuội → đồ đá → lò nung → nung sắt → đồ sắt.
  Công thức và số lượng nguyên liệu giống bản vanilla. Cô ấy đặt bàn chế tạo hoặc lò ra dùng, xong thì **thu về**.
- **Đói và ăn:** thanh no 0–20 giống người chơi. Đói thì lấy đồ ăn ra ăn (không ăn thịt sống trừ khi đói lả),
  no thì tự hồi máu.
- **Đi săn có chừng mực:** chỉ săn con trưởng thành và luôn chừa lại ít nhất 2 con mỗi loài.
- **Không phá đồ của bạn:** chỉ chặt cây có lá tự mọc và chỉ đào đá tự nhiên lộ ra ngoài. Không đào xuống dưới chân,
  không làm gì trong vòng 16 block quanh nhà và giường của bạn.
- **Đi theo hay làm việc:** khi bạn di chuyển, cô ấy bỏ việc để đi theo. Khi bạn đứng lại, cô ấy làm việc quanh bạn
  (bán kính 24 block, ban đêm thu lại 12 block). Hết việc thì cô ấy **đứng nghỉ** cạnh bạn chứ không chạy lung tung.

## Tâm trí

| | |
|---|---|
| **Tính cách** | 9 trọng số cố định: dũng cảm, chăm chỉ, tò mò, thích gần người, hiền, thích xây, thích đánh, nói nhiều, vui vẻ. Thêm một **lý tưởng** (bạn bè, phiêu lưu, tổ ấm, bảo vệ, làm việc). Các trọng số sinh từ UUID nên mỗi cô một khác. |
| **Nhu cầu và cảm xúc** | Đói, sợ, vui, chán, tò mò, năng lượng, giao tiếp. Chúng thay đổi theo thời gian và theo sự kiện: được tặng quà, bị đánh, bị quái cắn, trời tối, chủ về, hạ được quái… |
| **Ra quyết định (Utility AI)** | Mỗi giây cô ấy chấm điểm mọi việc có thể làm theo *nhu cầu × tính cách × kinh nghiệm*, rồi chọn việc điểm cao nhất. Ví dụ: đói thì ăn hoặc đi săn, chán hay tò mò thì đi khám phá, mệt hay sợ thì nghỉ cạnh bạn, cô đơn thì lại gần nói chuyện, thích xây thì trồng cây hoặc cắm đuốc ban đêm. Nhút nhát mà gặp quái lúc yếu thì chạy trốn. Việc khẩn cấp (đói lả, gặp nguy) được ngắt ngang việc đang làm. |
| **Trí nhớ** | Nhớ nhà, rừng, bãi đá, quặng, đàn thú, hồ nước, chỗ ngắm cảnh, **chỗ từng gặp nguy hiểm** (và tránh khi đi khám phá), **người chơi nào tốt hay xấu** với cô ấy, và một nhật ký 40 chuyện gần nhất. |
| **Học đơn giản** | Ghi lại mỗi việc ở mỗi vùng 64×64 thành công hay thất bại. Lần sau, việc hay thất bại ở vùng đó được chấm điểm thấp hơn. Kinh nghiệm cũ phai dần để còn thử lại. |
| **Nói chuyện** | Mỗi câu được ghép từ ý muốn nói (việc đang làm và lý do, chuyện đã nhớ, nơi đã thấy, cảm xúc) cùng cách nói theo tâm trạng, tính cách và độ thân thiết. Không có danh sách câu trả lời viết sẵn, và không cần mạng. |

Dữ liệu này nằm trong `<thế giới>/data/lymeowz.json`.

## Nói chuyện với cô ấy

Bạn cứ chat bình thường. Cô ấy nghe thấy trong phạm vi 32 block, hoặc khi bạn gọi tên (*Ly*, *LyMeowz*).

**Ra lệnh:** `đi theo`, `đứng yên`, `về nhà`, `lại đây`, `đây là nhà`, `chặt cây`, `đào đá`, `đi săn`, `làm việc đi`,
`đưa đồ`.

**Hỏi chuyện.** Cô ấy trả lời từ trạng thái và trí nhớ thật của mình. Các câu dưới đây lấy từ log server thật:

```
<bạn> cậu đói không?        <LyMeowz> Mình đói đói một chút (12/20) nha~
<bạn> cậu đang làm gì vậy?   <LyMeowz> Mình đang nhóm lò nướng đồ nè~
<bạn> mỏ đá ở đâu?           <LyMeowz> Mình nhớ có chỗ đào đá được ở phía tây, cách đây chừng 25 bước~
<bạn> cậu thích gì?          <LyMeowz> Mình là đứa hơi nhút nhát, hiền, chăm làm. Điều mình muốn nhất là ở bên bạn bè nè~
<bạn> cậu dễ thương quá      <LyMeowz> M-Mugyu~! Mặt mình nóng hết lên rồi!
```

Cô ấy cũng tự nói ra việc mình làm, kèm lý do, và kể những gì vừa thấy:
*"Hihi, mình định đi săn, sắp hết đồ ăn rồi nè~"* · *"A ha, phía tây bắc có nhiều cây lắm cách đây chừng 15 bước,
lần sau mình qua đó lấy gỗ đó~"*

Bạn khen hay chê, tặng quà hay đánh cô ấy đều làm thay đổi **độ thân thiết**. Cô ấy xưng hô khác đi theo độ thân thiết
(tớ/mình/tôi, cậu/bạn). Nếu bị đối xử tệ quá, cô ấy có thể **không nghe lời**.

> **Giới hạn:** không có mô hình ngôn ngữ thì cô ấy không hiểu được mọi câu tùy ý. Với câu lạ, cô ấy sẽ "Mugyu~?".
> Nếu muốn trò chuyện tự do hơn, bạn có thể bật LLM (Ollama, OpenAI…) trong config.

## Lệnh

```
/lymeowz summon | follow | stay | home | sethome | info | inv | give | dismiss
/lymeowz say <câu>             nói với cô ấy từ bất cứ đâu
/lymeowz reload                (OP) tải lại config
/lymeowz selftest              (OP) tự kiểm tra: dịch chuyển, chunk, đổi chiều không gian, ngất...
/lymeowz selftest survival     (OP) dựng khu thử và tua nhanh 20 phút để xem cô ấy sinh tồn
```

## Cài đặt

Bạn cần Minecraft **26.3**, [Fabric Loader](https://fabricmc.net/use/) 0.18.4 trở lên,
[Fabric API](https://modrinth.com/mod/fabric-api) và Java 25. Chép `lymeowzai-2.0.0+26.3.jar` vào thư mục `mods/`.

Từ bản 2.0, mod chỉ hỗ trợ Minecraft 26.3. Các bản cho 1.21.x–26.2 vẫn còn trong lịch sử git (bản 1.1.0)
nhưng không được phát triển tiếp.

## Cấu hình: `config/lymeowz.json`

`followRadius` (100), `workRadius` (24), `protectRadius` (16), `teleportDistance` (128), `chunkLoadRadius`,
`autoSpawnOnFirstJoin`, `idleChatter`, `goHomeWhenOwnerSleeps`, `hearingDistance`, `llm {...}`.

## Build

```bash
./gradlew build        # cần Java 25 → build/libs/lymeowzai-2.0.0+26.3.jar
```

## Mã nguồn

```
entity/LyMeowzEntity     thân thể: đi theo, về nhà, dịch chuyển, túi đồ, đói, tương tác
mind/Mind                nhu cầu & cảm xúc, sự kiện, nhìn & nhớ nơi chốn, quan hệ, học
mind/Decider             Utility AI chọn việc
mind/Personality         tính cách + lý tưởng
speech/Speech            ghép câu theo ý nghĩa + cảm xúc + xưng hô
speech/Understanding     hiểu câu hỏi, khen/chê; trả lời từ trạng thái và trí nhớ
survival/                túi đồ, đào block, chế tạo, các việc (chặt, đào, săn, nung, ăn, khám phá, trồng cây...)
world/                   lưu dữ liệu, tìm lại cô ấy khi vào game, giữ chunk
```

MIT License · KhanhLyNek
