# LyMeowz AI 🌸

> *"Mugyu~? Mình không hiểu lắm..."*

Mod Minecraft **Fabric** (từ **1.21** tới **26.3**) thêm **LyMeowz**, một cô bạn đồng hành AI để thế giới bớt cô đơn hơn.
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
/lymeowz selftest    (OP) tự kiểm tra mod trên phiên bản Minecraft đang chạy
```

Lần đầu vào thế giới, bạn được tặng sẵn một LyMeowz. Một LyMeowz không chủ (ví dụ tạo bằng
`/summon lymeowz:lymeowz`) sẽ nhận bạn làm chủ khi bạn chuột phải vào cô ấy.

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

1. Cài [Fabric Loader](https://fabricmc.net/use/) bản mới nhất (26.x cần 0.18.4 trở lên) và [Fabric API](https://modrinth.com/mod/fabric-api) cho phiên bản Minecraft của bạn.
2. Chọn đúng file theo bảng dưới rồi chép vào thư mục `mods/`.

| Minecraft | File | Java |
|---|---|---|
| 1.21 – 1.21.1 | `lymeowzai-1.1.0+1.21.1.jar` | 21 |
| 1.21.2 – 1.21.3 | `lymeowzai-1.1.0+1.21.3.jar` | 21 |
| 1.21.4 | `lymeowzai-1.1.0+1.21.4.jar` | 21 |
| 1.21.5 | `lymeowzai-1.1.0+1.21.5.jar` | 21 |
| 1.21.6 – 1.21.8 | `lymeowzai-1.1.0+1.21.8.jar` | 21 |
| 1.21.9 – 1.21.10 | `lymeowzai-1.1.0+1.21.10.jar` | 21 |
| 1.21.11 | `lymeowzai-1.1.0+1.21.11.jar` | 21 |
| 26.1 – 26.1.2 | `lymeowzai-1.1.0+26.1.2.jar` | 25 |
| 26.2 | `lymeowzai-1.1.0+26.2.jar` | 25 |
| 26.3 | `lymeowzai-1.1.0+26.3.jar` | 25 |

Chọn nhầm file cũng không sao: Fabric sẽ báo phiên bản không khớp thay vì làm hỏng thế giới.

Dữ liệu của cô ấy (chủ, nhà, chế độ, lịch sử trò chuyện) được lưu trong `<thế giới>/data/lymeowz.json`.

## Build

Cần **Java 25** (Gradle tự dùng Java 21 cho các bản 1.21.x nếu máy có cả hai).

```bash
./gradlew build                 # build mọi phiên bản → build/libs/
./gradlew :1.21.1:build         # chỉ một phiên bản
./gradlew :26.3:runClient       # chạy thử
```

## Kiểm thử

Mỗi file jar được kiểm tra trên **mọi** phiên bản Minecraft trong dải của nó:

- **Kiểm tra liên kết tĩnh:** mọi lời gọi tới Minecraft và Fabric API trong jar đều được đối chiếu với jar client
  của từng phiên bản. Cách này bắt được `NoSuchMethodError` trước khi chúng làm crash game.
- **Server thật:** dựng server Fabric bản phát hành, cho một bot người chơi vào chơi theo kịch bản
  (chào, Mugyu~, bị bỏ xa 300 block, thoát rồi vào lại, đứng đợi, sang Nether…), và chạy `/lymeowz selftest`.

Kết quả với bản 1.1.0:

| Minecraft | File jar | Liên kết tĩnh | Bot (18 mục) | Selftest (12 mục) |
|---|---|---|---|---|
| 1.21, 1.21.1 | 1.21.1 | ✅ | ✅ | ✅ |
| 1.21.2, 1.21.3 | 1.21.3 | ✅ | ✅ | ✅ |
| 1.21.4 | 1.21.4 | ✅ | ✅ | ✅ |
| 1.21.5 | 1.21.5 | ✅ | ✅ | ✅ |
| 1.21.6, 1.21.7, 1.21.8 | 1.21.8 | ✅ | ✅ | ✅ |
| 1.21.9, 1.21.10 | 1.21.10 | ✅ | ✅ | ✅ |
| 1.21.11 | 1.21.11 | ✅ | ✅ | ✅ |
| 26.1, 26.1.1, 26.1.2 | 26.1.2 | ✅ | ✅ | ✅ |
| 26.2 | 26.2 | ✅ | – (bot chưa hỗ trợ) | ✅ |
| 26.3 | 26.3 | ✅ | – (bot chưa hỗ trợ) | ✅ |

Phần vẽ nhân vật phía client chỉ được kiểm tra bằng liên kết tĩnh, chưa được mở game để nhìn tận mắt.

## Cấu trúc mã nguồn

Một mã nguồn chung (tên Mojang) cho mọi phiên bản, cộng một lớp `compat` nhỏ cho từng nhóm phiên bản
ở những chỗ API Minecraft thay đổi.

```
src/main/java/…          mã chung
  entity/LyMeowzEntity     AI chính: đi theo, đi chơi, về nhà, dịch chuyển, chống kẹt, ngất
  world/CompanionStore     dữ liệu lưu ra lymeowz.json (không phụ thuộc định dạng NBT của từng bản)
  world/CompanionTracker   vào game → đánh thức cô ấy, kiểm tra định kỳ, chống trùng lặp
  chat/Brain               hiểu câu chat, nhận diện ý định, gọi LLM
  chat/Lines               lời thoại (Mugyu~)
  command/SelfTest         /lymeowz selftest
compat/<nhóm>/            Compat, LyMeowzBase (server) và ClientCompat (renderer) theo phiên bản
  v1_21_1   1.21 – 1.21.1
  v1_21_2   1.21.2 – 1.21.4   (render state, hurtServer, TeleportTransition; hai bản build vì sendParticles đổi ở 1.21.4)
  v1_21_5   1.21.5 – 1.21.8   (TicketType mới, RespawnConfig, snapTo)
  v1_21_9   1.21.9 – 1.21.10  (AvatarRenderState, RespawnData, KEEP_DIMENSION_ACTIVE)
  v1_21_11  1.21.11 – 26.3    (Identifier, PermissionSet; 26.x không remap)
versions/<bản>/gradle.properties   phiên bản Minecraft, Fabric API, Java, nhóm compat, dải phiên bản hỗ trợ
```

MIT License · KhanhLyNek
