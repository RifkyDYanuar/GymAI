package com.modul.gymai.antarmuka.latihan

data class ExerciseDetail(
    val exerciseId: String,
    val definition: String,
    val steps: List<String>,
    val correctTechniques: List<String>,
    val wrongTechniques: List<String>,
    val tips: List<String>,
    val primaryMuscle: String,
    val primaryMuscleDesc: String,
    val muscleGroups: List<MuscleGroup>,
    val importanceDesc: String,
    val tutorialMediaName: String = "",
    val tutorialMediaType: TutorialMediaType = TutorialMediaType.MP4
)

data class MuscleGroup(
    val name: String,
    val type: MuscleType
)

enum class MuscleType { PRIMARY, SECONDARY, TERTIARY }

enum class TutorialMediaType { MP4, GIF }

object ExerciseDetailRepository {
    fun getDetail(exerciseId: String): ExerciseDetail? {
        return allDetails[exerciseId]
    }

    private val allDetails = mapOf(
        "1" to ExerciseDetail(
            exerciseId = "1",
            definition = "Squat adalah gerakan fundamental dalam latihan kekuatan yang melibatkan menurunkan tubuh dengan menekuk lutut dan pinggul, kemudian kembali ke posisi berdiri. Gerakan ini sangat efektif untuk membangun kekuatan dan massa otot tubuh bagian bawah sekaligus meningkatkan stabilitas core.",
            steps = listOf(
                "Pastikan seluruh tubuh terlihat jelas di kamera dari kepala sampai kaki.",
                "Posisikan tubuh menghadap samping serong ke kiri atau kanan kamera.",
                "Berdiri tegak dengan kaki selebar bahu, jari kaki sedikit mengarah ke luar 15-30 derajat.",
                "Posisikan barbell di trapezius atas jika memakai beban, atau luruskan tangan ke depan bila tanpa beban.",
                "Tarik napas, kencangkan core, dan jaga dada tetap tegak.",
                "Dorong pinggul ke belakang sambil menekuk lutut secara perlahan.",
                "Turunkan tubuh hingga paha sejajar atau sedikit di bawah posisi sejajar lantai.",
                "Dorong tumit ke lantai untuk kembali berdiri dengan pinggul dan lutut lurus penuh."
            ),
            correctTechniques = listOf(
                "Gerakan benar saat kedalaman squat cukup: pinggul turun hingga paha sejajar atau lebih rendah.",
                "Badan tetap tegak dan stabil, tidak terlalu membungkuk saat turun maupun naik.",
                "Tempo gerakan terkontrol, tidak turun dan naik terlalu cepat.",
                "Tubuh menghadap samping serong agar sudut pinggul, lutut, dan badan terbaca jelas."
            ),
            wrongTechniques = listOf(
                "Kedalaman squat kurang: pinggul belum turun cukup rendah saat repetisi selesai.",
                "Badan terlalu membungkuk saat turun atau kembali berdiri.",
                "Tempo squat terlalu cepat sehingga kontrol gerakan berkurang.",
                "Kaki atau seluruh tubuh tidak terlihat jelas di kamera."
            ),
            tips = listOf(
                "Gunakan sudut kamera samping serong agar posisi pinggul, lutut, dan pergelangan kaki lebih mudah dipantau.",
                "Pastikan area latihan cukup luas supaya seluruh tubuh tetap masuk frame.",
                "Mulai dengan bodyweight squat sebelum menambah beban."
            ),
            primaryMuscle = "Quadriceps & Glutes",
            primaryMuscleDesc = "Otot yang paling dominan bekerja dalam gerakan Squat",
            muscleGroups = listOf(
                MuscleGroup("Quadriceps (paha depan)", MuscleType.PRIMARY),
                MuscleGroup("Gluteus Maximus (otot bokong)", MuscleType.PRIMARY),
                MuscleGroup("Hamstrings (paha belakang)", MuscleType.SECONDARY),
                MuscleGroup("Gastrocnemius (betis)", MuscleType.SECONDARY),
                MuscleGroup("Core / Erector Spinae", MuscleType.TERTIARY),
                MuscleGroup("Adductors (paha dalam)", MuscleType.TERTIARY)
            ),
            importanceDesc = "Squat melatih lebih dari 200 otot sekaligus dan merupakan gerakan fungsional yang mensimulasikan aktivitas sehari-hari seperti duduk dan berdiri. Latihan ini terbukti meningkatkan kepadatan tulang dan metabolisme tubuh secara keseluruhan.",
            tutorialMediaName = "squat"
        ),
        "2" to ExerciseDetail(
            exerciseId = "2",
            definition = "Biceps Curl adalah gerakan isolasi yang berfokus pada penguatan otot bisep lengan atas. Gerakan ini melibatkan fleksi siku dengan membawa beban ke arah bahu, kemudian menurunkannya kembali secara terkontrol.",
            steps = listOf(
                "Pastikan seluruh tubuh terlihat jelas di kamera dari kepala sampai kaki.",
                "Posisikan tubuh menghadap ke samping kiri atau kanan kamera.",
                "Berdiri tegak atau duduk dengan punggung lurus.",
                "Pegang dumbbell di kedua tangan dengan telapak menghadap ke depan.",
                "Kunci siku di sisi tubuh dan tekuk siku untuk mengangkat dumbbell ke arah bahu secara perlahan.",
                "Turunkan beban secara perlahan hingga lengan hampir lurus penuh."
            ),
            correctTechniques = listOf(
                "Gerakan benar saat siku tetap diam di samping tubuh selama curl.",
                "Fleksi siku optimal: beban diangkat cukup tinggi lalu diturunkan sampai lengan hampir lurus.",
                "Tubuh tetap tegak dan tidak berayun untuk membantu mengangkat beban.",
                "Tempo naik dan turun terkontrol, tidak terlalu cepat."
            ),
            wrongTechniques = listOf(
                "Siku bergerak maju-mundur dari sisi tubuh selama curl.",
                "Range curl kurang: beban belum terangkat cukup tinggi.",
                "Tempo curl terlalu cepat saat mengangkat atau menurunkan beban.",
                "Lengan atau tubuh tidak terlihat jelas di kamera."
            ),
            tips = listOf(
                "Gunakan posisi kamera samping agar sudut siku lebih mudah dianalisis.",
                "Jaga jarak dengan kamera supaya seluruh tubuh tetap terlihat meski memegang beban.",
                "Fokus pada kontraksi bisep dan hindari menggunakan momentum tubuh."
            ),
            primaryMuscle = "Biceps Brachii",
            primaryMuscleDesc = "Otot bisep adalah penggerak utama dalam gerakan curl",
            muscleGroups = listOf(
                MuscleGroup("Biceps Brachii (bisep)", MuscleType.PRIMARY),
                MuscleGroup("Brachialis (bawah bisep)", MuscleType.SECONDARY),
                MuscleGroup("Brachioradialis (lengan bawah)", MuscleType.SECONDARY),
                MuscleGroup("Forearm Flexors", MuscleType.TERTIARY)
            ),
            importanceDesc = "Bisep yang kuat mendukung semua gerakan tarikan seperti pull-up dan row. Melatih bisep secara teratur meningkatkan kekuatan fungsional dan penampilan lengan atas secara keseluruhan.",
            tutorialMediaName = "bicepscurl"
        ),
        "3" to ExerciseDetail(
            exerciseId = "3",
            definition = "Lateral Raise adalah gerakan isolasi yang menargetkan deltoid lateral atau bahu samping untuk menciptakan tampilan bahu yang lebar dan kuat. Gerakan ini melibatkan pengangkatan lengan ke samping hingga sejajar dengan bahu.",
            steps = listOf(
                "Pastikan tubuh bagian atas sampai pinggul dan kedua lengan terlihat jelas di kamera.",
                "Posisikan tubuh menghadap depan ke kamera.",
                "Duduk atau berdiri tegak dengan punggung lurus, lalu pegang dumbbell di kedua sisi tubuh.",
                "Tekuk siku sedikit saat posisi siap sebelum mulai mengangkat.",
                "Angkat kedua lengan ke samping hingga sejajar dengan bahu, lalu turunkan kembali secara perlahan."
            ),
            correctTechniques = listOf(
                "Gerakan benar saat tangan terangkat sampai sejajar bahu, tidak lebih tinggi.",
                "Tempo gerakan terkontrol dari bawah ke atas lalu kembali ke bawah.",
                "Bahu tetap rileks dan tidak ikut terangkat saat mengangkat beban.",
                "Kedua lengan bergerak seimbang dengan tubuh tetap tegak, baik saat duduk maupun berdiri."
            ),
            wrongTechniques = listOf(
                "Tinggi angkatan kurang: lengan belum sampai sejajar bahu.",
                "Tangan atau lengan terangkat lebih tinggi dari bahu.",
                "Bahu ikut terangkat saat mengangkat beban.",
                "Gerakan tidak seimbang, pergelangan memimpin, atau tubuh condong."
            ),
            tips = listOf(
                "Gunakan sudut kamera depan agar kanan dan kiri bisa dibandingkan dengan jelas.",
                "Pilih beban ringan dan prioritaskan kontrol gerakan penuh.",
                "Jaga jarak dengan kamera agar kedua tangan dan pinggul tetap terlihat dari awal sampai akhir."
            ),
            primaryMuscle = "Lateral Deltoids",
            primaryMuscleDesc = "Deltoid lateral adalah target utama yang membentuk lebar bahu",
            muscleGroups = listOf(
                MuscleGroup("Lateral Deltoid (bahu samping)", MuscleType.PRIMARY),
                MuscleGroup("Anterior Deltoid (bahu depan)", MuscleType.SECONDARY),
                MuscleGroup("Supraspinatus (rotator cuff)", MuscleType.SECONDARY),
                MuscleGroup("Trapezius Upper", MuscleType.TERTIARY)
            ),
            importanceDesc = "Deltoid lateral memberikan ilusi bahu yang lebar dan membentuk rasio pinggang-bahu yang ideal. Otot ini juga penting untuk stabilitas bahu dan performa gerakan overhead.",
            tutorialMediaName = "lateralraise"
        ),
        "4" to ExerciseDetail(
            exerciseId = "4",
            definition = "Shoulder Press adalah gerakan compound yang melatih seluruh kompleks otot bahu dengan mendorong beban dari posisi bahu ke atas kepala. Gerakan ini merupakan salah satu indikator utama kekuatan tubuh bagian atas.",
            steps = listOf(
                "Pastikan seluruh tubuh terlihat jelas di kamera dari kepala sampai kaki.",
                "Posisikan tubuh menghadap depan ke kamera.",
                "Duduk atau berdiri dengan punggung lurus dan pegang dumbbell setinggi bahu.",
                "Telapak tangan menghadap ke depan dan siku membentuk sudut sekitar 90 derajat.",
                "Kencangkan core lalu dorong beban lurus ke atas hingga lengan hampir lurus penuh.",
                "Turunkan beban kembali ke posisi awal secara perlahan dan terkontrol."
            ),
            correctTechniques = listOf(
                "Gerakan benar saat dorongan lurus ke atas sampai siku hampir lurus.",
                "Punggung tetap stabil dan tidak melengkung berlebihan.",
                "Dorongan kedua lengan tetap simetris dari bawah sampai atas.",
                "Tempo dorong dan turun terkontrol, tidak terlalu cepat."
            ),
            wrongTechniques = listOf(
                "Dorongan belum hampir lurus ke atas atau punggung tidak stabil.",
                "Dorongan kanan dan kiri tidak simetris.",
                "Tempo shoulder press terlalu cepat.",
                "Kedua lengan tidak terlihat jelas atau tubuh tidak menghadap depan kamera."
            ),
            tips = listOf(
                "Gunakan posisi kamera depan agar simetri gerak kedua lengan lebih mudah dianalisis.",
                "Sisakan ruang kosong di atas kepala supaya beban tidak keluar dari frame saat diangkat.",
                "Kencangkan core sepanjang gerakan untuk menjaga postur tetap stabil."
            ),
            primaryMuscle = "Shoulders & Triceps",
            primaryMuscleDesc = "Deltoid anterior dan triceps bekerja dominan dalam gerakan press ke atas",
            muscleGroups = listOf(
                MuscleGroup("Anterior Deltoid (bahu depan)", MuscleType.PRIMARY),
                MuscleGroup("Triceps Brachii (belakang lengan)", MuscleType.PRIMARY),
                MuscleGroup("Lateral Deltoid (bahu samping)", MuscleType.SECONDARY),
                MuscleGroup("Upper Pectoralis (dada atas)", MuscleType.SECONDARY),
                MuscleGroup("Core Stabilizers", MuscleType.TERTIARY)
            ),
            importanceDesc = "Shoulder press adalah fondasi kekuatan tubuh bagian atas yang mendukung semua gerakan pushing seperti bench press dan push-up. Otot bahu yang kuat juga membantu melindungi sendi bahu yang sangat mobile.",
            tutorialMediaName = "shoulderpress"
        )
    )
}
