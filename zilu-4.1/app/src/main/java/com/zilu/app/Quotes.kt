package com.zilu.app

import java.time.LocalDate

data class Quote(val en: String, val zh: String, val author: String)

/** 每天一句，按顺序轮换，连续两天不会重复。 */
private val quotes = listOf(
    Quote("The journey of a thousand miles begins with a single step.", "千里之行，始于足下。", "Lao Tzu 老子"),
    Quote("It always seems impossible until it's done.", "事情在完成之前，总是看起来不可能。", "Nelson Mandela"),
    Quote("Discipline is the bridge between goals and accomplishment.", "自律是连接目标与成就的桥梁。", "Jim Rohn"),
    Quote("It does not matter how slowly you go as long as you do not stop.", "走得慢没关系，只要不停下。", "Confucius 孔子"),
    Quote("Success is the sum of small efforts, repeated day in and day out.", "成功是日复一日小努力的总和。", "Robert Collier"),
    Quote("Well begun is half done.", "良好的开端是成功的一半。", "Aristotle 亚里士多德"),
    Quote("Knowing is not enough; we must apply.", "知道还不够，我们必须去运用。", "Goethe 歌德"),
    Quote("Genius is one percent inspiration and ninety-nine percent perspiration.", "天才是百分之一的灵感加百分之九十九的汗水。", "Thomas Edison"),
    Quote("Do what you can, with what you have, where you are.", "在你所在之处，用你所拥有的，做你所能做的。", "Theodore Roosevelt"),
    Quote("Fall seven times, stand up eight.", "七次跌倒，八次爬起。", "Japanese proverb 日本谚语"),
    Quote("Lost time is never found again.", "逝去的时间永不复返。", "Benjamin Franklin"),
    Quote("Well done is better than well said.", "做得好胜过说得好。", "Benjamin Franklin"),
    Quote("Energy and persistence conquer all things.", "精力与坚持能征服一切。", "Benjamin Franklin"),
    Quote("An investment in knowledge pays the best interest.", "对知识的投资回报最高。", "Benjamin Franklin"),
    Quote("Courage is not the absence of fear, but the triumph over it.", "勇气不是没有恐惧，而是战胜恐惧。", "Nelson Mandela"),
    Quote("Don't count the days, make the days count.", "不要细数日子，要让每一天都有价值。", "Muhammad Ali"),
    Quote("Small deeds done are better than great deeds planned.", "已做的小事胜过计划中的大事。", "Peter Marshall"),
    Quote("What you do today can improve all your tomorrows.", "你今天所做的，能改善你所有的明天。", "Ralph Marston"),
    Quote("The expert in anything was once a beginner.", "任何领域的专家，都曾是初学者。", "Helen Hayes"),
    Quote("Fortune favors the bold.", "幸运眷顾勇敢的人。", "Virgil 维吉尔"),
    Quote("You miss 100% of the shots you don't take.", "你不出手，就百分之百不会命中。", "Wayne Gretzky"),
    Quote("Whether you think you can, or you think you can't, you're right.", "无论你认为自己行还是不行，你都是对的。", "Henry Ford"),
    Quote("Perseverance is not a long race; it is many short races one after the other.", "坚持不是一场长跑，而是一个接一个的短跑。", "Walter Elliot"),
    Quote("Hard choices, easy life. Easy choices, hard life.", "选择艰难，人生轻松；选择容易，人生艰难。", "Jerzy Gregorek"),
    Quote("Start where you are. Use what you have. Do what you can.", "从你所在之处开始，用你所拥有的，做你所能做的。", "Arthur Ashe"),
    Quote("Be not afraid of growing slowly; be afraid only of standing still.", "不怕进步慢，只怕原地站。", "Chinese proverb 中国谚语"),
    Quote("Rome wasn't built in a day.", "罗马不是一天建成的。", "Proverb 谚语"),
    Quote("Motivation gets you going, but discipline keeps you growing.", "动力让你出发，自律让你成长。", "John C. Maxwell"),
    Quote("A man who conquers himself is greater than one who conquers a thousand men in battle.", "战胜自己的人，胜过在战场上战胜千人的人。", "Buddha 佛陀"),
    Quote("Do not wait to strike till the iron is hot; make it hot by striking.", "不要等铁热了再打，要靠打让它变热。", "W. B. Yeats"),
    Quote("He who has a why to live can bear almost any how.", "知道为何而活的人，几乎能忍受任何境遇。", "Friedrich Nietzsche 尼采"),
    Quote("As heaven moves ceaselessly, the noble person strives without rest.", "天行健，君子以自强不息。", "《周易》 I Ching"),
    Quote("Mastery comes from diligence and is lost to play; conduct is built by reflection and ruined by carelessness.", "业精于勤，荒于嬉；行成于思，毁于随。", "韩愈 Han Yu"),
    Quote("Learning without thought is labor lost; thought without learning is perilous.", "学而不思则罔，思而不学则殆。", "孔子 Confucius"),
    Quote("Let your ambition aim high and far.", "志当存高远。", "诸葛亮 Zhuge Liang"),
    Quote("Carve without giving up, and even metal and stone can be engraved.", "锲而不舍，金石可镂。", "荀子 Xunzi"),
    Quote("Without accumulating small steps, you cannot travel a thousand miles.", "不积跬步，无以至千里。", "荀子 Xunzi"),
    Quote("Walking with two others, I will always find a teacher among them.", "三人行，必有我师焉。", "孔子 Confucius"),
    Quote("A sword's edge comes from grinding; the plum blossom's fragrance comes from bitter cold.", "宝剑锋从磨砺出，梅花香自苦寒来。", "古训 Chinese saying"),
    Quote("The best time to plant a tree was twenty years ago. The second best time is now.", "种一棵树最好的时间是二十年前，其次是现在。", "Chinese proverb 中国谚语"),
    Quote("We are what we repeatedly do. Excellence, then, is not an act, but a habit.", "我们重复做的事造就了我们。卓越不是行为，而是习惯。", "Will Durant"),
    Quote("The secret of getting ahead is getting started.", "领先的秘诀，就是先开始。", "Mark Twain 马克·吐温"),
    Quote("Action is the foundational key to all success.", "行动是一切成功的根本。", "Pablo Picasso 毕加索"),
    Quote("You do not rise to the level of your goals. You fall to the level of your systems.", "你不会升到目标的高度，只会落到系统的水平。", "James Clear"),
    Quote("Diligence redeems stupidity, while diligence wasted is still diligence.", "勤能补拙，但别把勤奋浪费在原地打转上。", "Chinese saying 中国谚语"),
    Quote("He who asks a question is a fool for five minutes; he who does not ask remains a fool forever.", "提问的人笨五分钟，不提问的人笨一辈子。", "Chinese proverb 中国谚语"),
    Quote("Simplicity is the ultimate sophistication.", "至简即至繁。", "Leonardo da Vinci 达·芬奇"),
    Quote("Nothing will work unless you do.", "你不去做，什么都不会成。", "Maya Angelou"),
    Quote("A year from now you may wish you had started today.", "一年后的你，会希望今天就开始。", "Karen Lamb"),
    Quote("Great things are done by a series of small things brought together.", "大事，是由一件件小事攒起来的。", "Vincent van Gogh 梵高")
)

/**
 * 按日期确定性取一句：每天自动换，连续两天不会拿到同一句。
 * 乘 7 是为了把相邻日期错开，避免连续几天都出自同一位作者；7 与语录总数互质，能轮完全部条目。
 */
fun quoteOfDay(day: LocalDate): Quote =
    quotes[Math.floorMod(day.toEpochDay() * 7L, quotes.size.toLong()).toInt()]
