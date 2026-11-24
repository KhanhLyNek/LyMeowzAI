# LyMeowz AI 🌸

> *"Mugyu~? Mình không hiểu lắm..."*

Mod Minecraft **Fabric 1.21.1** thêm **LyMeowz**, một cô bạn đồng hành AI để thế giới bớt cô đơn hơn.
Cô ấy đi chơi cùng bạn, tự tìm bạn khi bị lạc, tự về nhà khi bạn đi ngủ hoặc thoát game, và hay nói
**"Mugyu~"** khi không hiểu hoặc khi ngại ngùng.

## Tính năng

| | |
|---|---|
| 🚶 **Đi chơi tự do** | Đi khám phá quanh bạn trong bán kính **100 block**, thỉnh thoảng chạy về cạnh bạn. Ở trong hang hoặc ban đêm thì cô ấy đi sát bạn hơn. |
| 🔎 **Tự tìm người chơi** | Nếu bạn ở xa quá 128 block, sang Nether/End hay dùng `/tp`, cô ấy tự dịch chuyển tới một chỗ an toàn cạnh bạn. Nếu bị kẹt đường, cô ấy cũng tự dịch chuyển. |
| 🧱 **Tự giữ chunk** | Cô ấy giữ chunk quanh mình bằng ticket tự hết hạn, nên không bị "đóng băng" khi đi xa bạn. Ticket tự nhả khi cô ấy đứng đợi hoặc đã về tới nhà, nên không có chunk nào bị load mãi. |
| 🏠 **Tự về nhà** | Khi bạn thoát game, đi ngủ, hoặc bảo cô ấy về nhà. Nhà mặc định là giường hoặc điểm hồi sinh của bạn; bạn có thể đặt nhà riêng. |
| 💤 **Không bao giờ mất cô ấy** | Khi bạn vào lại game, mod load chỗ cô ấy đang ngủ để đánh thức cô ấy. Nếu không tìm thấy, mod tạo lại cô ấy, và bản cũ sẽ tự gỡ khi được load để không bị trùng. |
| ⚔️ **Bảo vệ bạn** | Đánh quái đang tấn công bạn, hoặc quái bạn đang đánh, trừ creeper. Khi gần hết máu, cô ấy không chết mà **ngất rồi về nhà nghỉ**. |
| 💬 **Nói chuyện** | Hiểu câu lệnh tiếng Việt, kể cả viết có dấu hay không dấu, và tiếng Anh. Có thể kết nối thêm LLM (Ollama/OpenAI…) để trò chuyện tự do. |

## Nói chuyện với cô ấy

Bạn chat bình thường. Cô ấy nghe thấy khi đứng cách bạn dưới 32 block, hoặc khi bạn gọi tên (*Ly*, *LyMeowz*).

| Bạn nói | Cô ấy làm |
|---|---|
| `đi theo`, `đi thôi`, `follow` | Đi theo / đi chơi quanh bạn |
| `đứng yên`, `đợi ở đây`, `stay` | Đứng đợi |
| `về nhà`, `go home` | Về nhà |
| `lại đây`, `đến đây`, `come` | Chạy tới bạn, hoặc dịch chuyển tới nếu bạn ở xa |
| `đây là nhà`, `sethome` | Đặt nhà tại chỗ bạn đứng |
| `dễ thương quá`, `yêu cậu`, `cute`… | *"M-Mugyu~! Đ-đừng nói vậy chứ... ngại quá à~"* |
| câu cô ấy không hiểu | *"Mugyu~? Cậu nói lại được không?"* |

**Tương tác:**

- Chuột phải: chuyển giữa đi theo và đứng đợi.
- Shift + chuột phải: đặt nhà tại chỗ cô ấy đứng.
- Cho đồ ăn: hồi máu cho cô ấy.
- Tặng hoa: …Mugyu~

## Lệnh

```
/lymeowz summon      gọi cô ấy tới (hoặc tạo mới nếu chưa có)
/lymeowz follow | stay | home
/lymeowz sethome     đặt nhà tại chỗ bạn đứng
/lymeowz info        cô ấy đang ở đâu, làm gì
/lymeowz say <câu>   nói với cô ấy từ bất cứ đâu
/lymeowz dismiss     tạm biệt cô ấy
/lymeowz reload      (OP) tải lại config
```

Lần đầu vào thế giới, bạn được tặng sẵn một LyMeowz. Ngoài ra còn có **Trứng triệu hồi LyMeowz** trong tab
Spawn Eggs: bạn đặt trứng xuống rồi chuột phải để nhận cô ấy.

## Cấu hình: `config/lymeowz.json`

```json
{
  "name": "LyMeowz",
  "followRadius": 100,
  "teleportDistance": 128,
  "chunkLoadRadius": 3,
  "autoSpawnOnFirstJoin": true,
  "idleChatter": true,
  "goHomeWhenOwnerSleeps": true,
  "hearingDistance": 32,
  "llm": {
    "enabled": false,
    "endpoint": "http://localhost:11434/v1/chat/completions",
    "model": "qwen2.5:7b",
    "apiKey": "",
    "timeoutSeconds": 20,
    "maxHistory": 12
  }
}
```

### Bật trò chuyện bằng AI (tùy chọn)

Mod hoạt động với bất kỳ API nào tương thích OpenAI Chat Completions. Cách dễ nhất là chạy miễn phí
trên máy bạn bằng [Ollama](https://ollama.com):

```bash
ollama pull qwen2.5:7b
```

Sau đó đặt `"enabled": true` trong config. Lệnh như "về nhà" vẫn được xử lý tại chỗ, không cần mạng.
Những câu cô ấy không hiểu sẽ được gửi tới mô hình, kèm tính cách và câu cửa miệng "Mugyu~". Mô hình cũng có thể
ra lệnh cho cô ấy bằng các thẻ `[FOLLOW]`, `[STAY]`, `[HOME]`, `[COME]`.

## Cài đặt

1. Cài [Fabric Loader](https://fabricmc.net/use/) cho Minecraft 1.21.1 và [Fabric API](https://modrinth.com/mod/fabric-api).
2. Chép `lymeowzai-1.0.0.jar` vào thư mục `mods/`.

## Build

Cần Java 21.

```bash
./gradlew build          # jar nằm ở build/libs/
./gradlew runClient      # chạy thử
```

## Cấu trúc mã nguồn

```
entity/LyMeowzEntity   AI chính: đi theo, đi chơi, về nhà, dịch chuyển, chống kẹt, ngất
world/ChunkKeeper      chunk ticket tự hết hạn
world/CompanionState   lưu cô ấy ở đâu (theo thế giới) để tìm lại khi chunk đã unload
world/CompanionTracker vào game → đánh thức cô ấy, kiểm tra định kỳ, chống trùng lặp
chat/Brain             hiểu câu chat, nhận diện ý định, gọi LLM
chat/Lines             lời thoại (Mugyu~)
chat/LlmClient         gọi API kiểu OpenAI bất đồng bộ
```

MIT License · KhanhLyNek
