package com.khanhly.lymeowz.chat;

import net.minecraft.util.RandomSource;

/**
 * Lời thoại của LyMeowz. Câu cửa miệng "Mugyu~" xuất hiện khi cô ấy
 * không hiểu hoặc ngại ngùng.
 */
public final class Lines {
    private Lines() {}

    public static final String[] FIRST_MEET = {
            "A-a... chào cậu! Mình là %s. Thế giới này rộng quá nên... mình đi cùng cậu được không? Mugyu~",
            "Cuối cùng cũng gặp được ai đó rồi! Mình là %s, từ giờ mình sẽ đi theo cậu nhé~"
    };
    public static final String[] GREET = {
            "Chào cậu~ Hôm nay mình đi đâu chơi đây?",
            "Hii~ Mình ở đây nè!",
            "A, cậu gọi mình hả? Hehe~"
    };
    public static final String[] WELCOME_BACK = {
            "Cậu về rồi! Mình đợi mãi đó... Mugyu~",
            "Tìm thấy cậu rồi! Đừng bỏ mình lại lâu vậy nữa nha~",
            "Ơ! Cậu quay lại rồi~ Mình nhớ cậu lắm đó."
    };
    public static final String[] FOLLOW = {
            "Okie~ Mình đi theo cậu nè!",
            "Đi thôi đi thôi~",
            "Cậu đi đâu mình đi đó~"
    };
    public static final String[] STAY = {
            "Ừm, mình đứng đây đợi cậu nhé.",
            "Mình sẽ ngoan ngoãn đứng đây~ Đừng quên quay lại đó!"
    };
    public static final String[] GO_HOME = {
            "Mình về nhà trước nha~",
            "Về nhà thôi~ Mình sẽ đợi cậu ở nhà!"
    };
    public static final String[] ARRIVED_HOME = {
            "Về tới nhà rồi~ Ấm áp ghê.",
            "Nhà mình đây rồi~"
    };
    public static final String[] HOME_SET = {
            "Đây là nhà mới của tụi mình hả? Thích quá~",
            "Mình nhớ chỗ này rồi! Nhà của tụi mình~"
    };
    public static final String[] COME = {
            "Tới liền tới liền~!",
            "Đợi mình với~!"
    };
    public static final String[] SEARCHING = {
            "Cậu đi đâu mất rồi... để mình tìm~",
            "Ơ, cậu đâu rồi? Đợi mình chút!"
    };
    public static final String[] FOUND = {
            "Tìm thấy cậu rồi! Hehe~",
            "Phù... theo kịp rồi~"
    };
    public static final String[] EMBARRASSED = {
            "M-Mugyu~! Đ-đừng nói vậy chứ... ngại quá à~",
            "Mugyu~... mặt mình nóng hết lên rồi nè...",
            "Mugyu~! C-cậu nói gì vậy chứ... (*/ω＼*)",
            "Mugyu~... mình... mình cũng vậy đó. Q-quên đi!"
    };
    public static final String[] CONFUSED = {
            "Mugyu~? Mình không hiểu lắm...",
            "Mugyu~? Cậu nói lại được không?",
            "Ơ... Mugyu~? Cái đó là gì vậy?",
            "Mugyu~... đầu mình xoay vòng vòng rồi."
    };
    public static final String[] HURT_BY_OWNER = {
            "Mugyu~! Đau đó nha! Sao cậu đánh mình...",
            "Á! Mugyu~... mình làm gì sai hả?"
    };
    public static final String[] FAINT = {
            "Mugyu~... mình hơi mệt... mình về nhà nghỉ chút nhé...",
            "Ui da... mình về nhà băng bó đã... đến đón mình nha~"
    };
    public static final String[] FIGHT = {
            "Tránh xa bạn mình ra!",
            "Để mình lo con này!",
            "Hya~!"
    };
    public static final String[] FED = {
            "Ngon quá~! Cảm ơn cậu nha~",
            "Măm măm~ Cậu tốt với mình ghê."
    };
    public static final String[] GIFT_FLOWER = {
            "T-tặng mình hả...? Mugyu~... đẹp quá...",
            "Mugyu~! Hoa cho mình sao... mình sẽ giữ thật kỹ~"
    };
    public static final String[] STRANGER = {
            "Mugyu~... cậu là ai vậy? Mình đang đợi bạn mình...",
            "Ơ... Mugyu~? Mình không quen cậu..."
    };
    public static final String[] NIGHT = {
            "Trời tối rồi... ở gần mình nhé, mình hơi sợ.",
            "Đêm rồi~ Cẩn thận zombie đó!"
    };
    public static final String[] OWNER_SLEEP = {
            "Cậu đi ngủ hả? Mình cũng về nhà đây~ Ngủ ngon nha!",
            "Ngủ ngon~ Mai gặp lại nhé!"
    };
    public static final String[] OWNER_LEFT = {
            "Cậu đi rồi à... mình về nhà đợi cậu nhé.",
    };
    public static final String[] IDLE = {
            "Cậu ơi, nhìn kìa! Mây hình con mèo~",
            "Hôm nay vui ghê~",
            "Mình thích đi chơi với cậu lắm đó.",
            "Lalala~ ♪",
            "Cậu có thấy cái hang đằng kia không? Hơi đáng sợ ha...",
            "Ước gì có bánh kem ăn~",
            "Thế giới này rộng thật đó... may mà có cậu."
    };
    public static final String[] THANKS = {
            "Hehe~ Không có gì đâu!",
            "Mugyu~ cậu khách sáo quá à."
    };
    public static final String[] HOW_ARE_YOU = {
            "Mình khỏe lắm~ Đi cùng cậu thì lúc nào cũng vui!",
            "Mình ổn nè~ Còn cậu thì sao?"
    };
    public static final String[] WHO_ARE_YOU = {
            "Mình là %s nè~ Bạn đồng hành của cậu đó!",
    };

    public static String pick(String[] lines, RandomSource random) {
        return lines[random.nextInt(lines.length)];
    }
}
