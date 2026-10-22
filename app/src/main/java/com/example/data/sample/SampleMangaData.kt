package com.example.data.sample

import com.example.R
import com.example.data.model.MangaBubble
import com.example.data.model.MangaTranslationResult
import com.example.data.model.MangaVocab

data class SampleMangaPage(
    val id: String,
    val title: String,
    val genre: String,
    val drawableResId: Int,
    val description: String,
    val result: MangaTranslationResult
)

object SampleMangaData {
    val samplePages: List<SampleMangaPage> = listOf(
        SampleMangaPage(
            id = "sample_battle",
            title = "Clash of Rivals",
            genre = "Shonen Action",
            drawableResId = R.drawable.sample_manga_battle,
            description = "High-stakes shonen confrontation with shouting bubbles, speedlines, and sound effects.",
            result = MangaTranslationResult(
                sceneSummary = "A fierce duel between rivals amidst heavy battle dust. The villain mocks the protagonist's exhaustion, but the hero summons latent resolve.",
                readingOrder = "RTL (Right-to-Left, Top-to-Bottom)",
                overallTone = "Dramatic & Intense",
                bubbles = listOf(
                    MangaBubble(
                        id = "bubble_1",
                        // top bubble: [ymin, xmin, ymax, xmax] normalized
                        box2d = listOf(100, 80, 260, 420),
                        japanese = "お前の力はそんなものか！？",
                        romaji = "Omae no chikara wa sonna mono ka!?",
                        english = "Is that all the power you've got!?",
                        literal = "Your power, is it such a thing!?",
                        type = "shout",
                        notes = "Casual masculine antagonist tone ('omae' used provocatively). Rhetorical interrogation.",
                        vocabulary = listOf(
                            MangaVocab("お前", "おまえ (omae)", "You (informal / confrontational)", "N5"),
                            MangaVocab("力", "ちから (chikara)", "Power / Strength", "N5"),
                            MangaVocab("そんなもの", "そんなもの (sonna mono)", "Such a thing / trivial matter", "N4")
                        )
                    ),
                    MangaBubble(
                        id = "bubble_2",
                        // sfx in the middle
                        box2d = listOf(460, 240, 560, 750),
                        japanese = "ドゴォォン！",
                        romaji = "DOGOOON!",
                        english = "*KABOOOOM! / CRAAASH!*",
                        literal = "Heavy impact explosion sound effect",
                        type = "sfx",
                        notes = "Manga onomatopoeia for seismic blast or crushing blow.",
                        vocabulary = listOf(
                            MangaVocab("ドゴォォン", "どごぉぉん (dogoon)", "Heavy thundering blast / explosion SFX", "SFX")
                        )
                    ),
                    MangaBubble(
                        id = "bubble_3",
                        // bottom right bubble
                        box2d = listOf(630, 540, 840, 930),
                        japanese = "まだだ…ここからが本気だッ！！",
                        romaji = "Mada da... koko kara ga honki da!!",
                        english = "Not yet... This is where I get serious!!",
                        literal = "Not yet... From here on is true seriousness!!",
                        type = "shout",
                        notes = "Classic shonen determination trope. 'Honki' (本気) signifies true full strength.",
                        vocabulary = listOf(
                            MangaVocab("まだ", "まだ (mada)", "Not yet / still", "N5"),
                            MangaVocab("ここから", "ここから (koko kara)", "From now on / starting here", "N5"),
                            MangaVocab("本気", "ほんき (honki)", "Serious / real power / earnest", "N3")
                        )
                    )
                )
            )
        ),
        SampleMangaPage(
            id = "sample_romance",
            title = "Rooftop Confession",
            genre = "Romance / Slice of Life",
            drawableResId = R.drawable.sample_manga_romance,
            description = "Gentle romantic dialogue on a school rooftop with subtle emotional nuance and narration box.",
            result = MangaTranslationResult(
                sceneSummary = "Two high school students meet by the chain-link fence at sunset. A quiet confession that catches the boy by surprise.",
                readingOrder = "RTL (Right-to-Left, Top-to-Bottom)",
                overallTone = "Gentle & Sentimental",
                bubbles = listOf(
                    MangaBubble(
                        id = "bubble_r1",
                        // narration box top left
                        box2d = listOf(35, 50, 140, 420),
                        japanese = "放課後の屋上——夕暮れの風が通り抜ける。",
                        romaji = "Hōkago no okujō—— yūgure no kaze ga tōrinukeru.",
                        english = "The rooftop after school—— The twilight breeze whispers by.",
                        literal = "After school's rooftop—— Dusk wind passes through.",
                        type = "narration",
                        notes = "Narration box setting the mood. Uses evocative sensory language.",
                        vocabulary = listOf(
                            MangaVocab("放課後", "ほうかご (hōkago)", "After school", "N3"),
                            MangaVocab("屋上", "おくじょう (okujō)", "Rooftop", "N3"),
                            MangaVocab("夕暮れ", "ゆうぐれ (yūgure)", "Twilight / Dusk", "N2"),
                            MangaVocab("風", "かぜ (kaze)", "Wind / Breeze", "N5")
                        )
                    ),
                    MangaBubble(
                        id = "bubble_r2",
                        // girl's speech bubble top right
                        box2d = listOf(140, 500, 320, 920),
                        japanese = "ずっと前から…伝えたかったの。",
                        romaji = "Zutto mae kara... tsutaetakatta no.",
                        english = "For so long now... I've wanted to tell you.",
                        literal = "From long before... wanted to convey.",
                        type = "bubble",
                        notes = "Feminine sentence-ending particle 'no' denoting tender emotional sincerity.",
                        vocabulary = listOf(
                            MangaVocab("ずっと前から", "ずっとまえから (zutto mae kara)", "For a long time / since way back", "N4"),
                            MangaVocab("伝える", "つたえる (tsutaeru)", "To convey / to communicate feelings", "N4")
                        )
                    ),
                    MangaBubble(
                        id = "bubble_r3",
                        // boy's speech bubble middle left
                        box2d = listOf(560, 120, 760, 560),
                        japanese = "えっ…それって、本当のことなのか…！？",
                        romaji = "E'... sorette, hontō no koto na no ka...!?",
                        english = "Wait... Does that mean... you really mean it...!?",
                        literal = "Eh... That thing, is it true matter...!?",
                        type = "bubble",
                        notes = "Hesitant and startled response with rising intonation.",
                        vocabulary = listOf(
                            MangaVocab("本当", "ほんとう (hontō)", "Truth / Reality / Really", "N5"),
                            MangaVocab("こと", "こと (koto)", "Matter / Concept / Thing", "N5")
                        )
                    ),
                    MangaBubble(
                        id = "bubble_r4",
                        // heartbeat sfx bottom right
                        box2d = listOf(780, 580, 900, 920),
                        japanese = "ドキッ…",
                        romaji = "Doki...",
                        english = "*Heart skips a beat... / Thump*",
                        literal = "Manga heartbeat sound effect",
                        type = "sfx",
                        notes = "Classic manga sound effect expressing nervousness, romantic surprise, or racing heart.",
                        vocabulary = listOf(
                            MangaVocab("ドキッ", "どきっ (doki)", "Heart pounding / startling heartbeat SFX", "SFX")
                        )
                    )
                )
            )
        ),
        SampleMangaPage(
            id = "sample_magic",
            title = "Forbidden Grimoire",
            genre = "Fantasy RPG",
            drawableResId = R.drawable.sample_manga_magic,
            description = "High fantasy battle incantation with arcane seal narrative and magical incantations.",
            result = MangaTranslationResult(
                sceneSummary = "Inside the deepest sanctuary of the forbidden temple, an ancient seal fractures, prompting an emergency spell release.",
                readingOrder = "RTL (Right-to-Left, Top-to-Bottom)",
                overallTone = "Arcane & Mystical",
                bubbles = listOf(
                    MangaBubble(
                        id = "bubble_m1",
                        // Narration box top left
                        box2d = listOf(35, 50, 130, 540),
                        japanese = "禁忌の神殿 深部——封印されし古代兵器",
                        romaji = "Kinki no shinden shinbu—— fūin sareshi kodai heiki",
                        english = "Deep within the Forbidden Shrine—— The ancient weapon once sealed away.",
                        literal = "Taboo temple deep part—— Sealed ancient weapon.",
                        type = "narration",
                        notes = "High-fantasy terminology with archaic passive grammatical marker 'sareshi'.",
                        vocabulary = listOf(
                            MangaVocab("禁忌", "きんき (kinki)", "Taboo / Forbidden", "N1"),
                            MangaVocab("神殿", "しんでん (shinden)", "Temple / Shrine / Sanctuary", "N2"),
                            MangaVocab("封印", "ふういん (fūin)", "Seal / Binding enchantment", "N2"),
                            MangaVocab("古代兵器", "こだいへいき (kodai heiki)", "Ancient weapon / artifact", "N2")
                        )
                    ),
                    MangaBubble(
                        id = "bubble_m2",
                        // Top right warning
                        box2d = listOf(130, 550, 300, 930),
                        japanese = "封印が解かれた…！急いで結界を張れ！",
                        romaji = "Fūin ga tokareta...! Isoide kekkai o hare!",
                        english = "The seal is broken...! Quick, raise the barrier!",
                        literal = "Seal was undone...! Hurry and deploy barrier!",
                        type = "shout",
                        notes = "Imperative command form 'hare' (張れ) used under life-and-death urgency.",
                        vocabulary = listOf(
                            MangaVocab("解く", "とく (toku)", "To untie / to break a seal", "N3"),
                            MangaVocab("急ぐ", "いそぐ (isogu)", "To hurry / hasten", "N4"),
                            MangaVocab("結界", "けっかい (kekkai)", "Magical barrier / ward", "N1")
                        )
                    ),
                    MangaBubble(
                        id = "bubble_m3",
                        // Spell cast bottom left
                        box2d = listOf(520, 80, 730, 420),
                        japanese = "古代魔法発動！雷神の怒りィィ！",
                        romaji = "Kodai mahō hatsudō! Raijin no ikariiii!",
                        english = "Ancient Magic: Invoke! Wrath of the Thunder God!!",
                        literal = "Ancient magic activation! Thunder God's rage!!",
                        type = "shout",
                        notes = "Anime spell shout naming the mythological deity Raijin (雷神).",
                        vocabulary = listOf(
                            MangaVocab("魔法", "まほう (mahō)", "Magic / Sorcery", "N4"),
                            MangaVocab("発動", "はつどう (hatsudō)", "Activation / Invocation", "N2"),
                            MangaVocab("雷神", "らいじん (raijin)", "Thunder God", "Mythology"),
                            MangaVocab("怒り", "いかり (ikari)", "Wrath / Rage / Fury", "N3")
                        )
                    ),
                    MangaBubble(
                        id = "bubble_m4",
                        // Villain/Observer disbelief bottom right
                        box2d = listOf(620, 560, 840, 930),
                        japanese = "バカな…魔力量が桁違いすぎる…！",
                        romaji = "Baka na... maryokuryō ga ketachigai sugiru...!",
                        english = "Impossible... Their mana reserves are completely off the charts...!",
                        literal = "Foolish... Mana quantity is too different by orders of magnitude...!",
                        type = "bubble",
                        notes = "'Ketachigai' (桁違い) literally means 'different by digits/orders of magnitude'.",
                        vocabulary = listOf(
                            MangaVocab("バカな", "ばかな (baka na)", "Impossible / Ridiculous", "N4"),
                            MangaVocab("魔力", "まりょく (maryoku)", "Magic power / Mana", "N2"),
                            MangaVocab("桁違い", "けたちがい (ketachigai)", "In a different league / orders of magnitude", "N1")
                        )
                    )
                )
            )
        )
    )
}
