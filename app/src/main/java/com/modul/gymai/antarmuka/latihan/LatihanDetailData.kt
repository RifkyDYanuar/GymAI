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
    val importanceDesc: String
)

data class MuscleGroup(
    val name: String,
    val type: MuscleType
)

enum class MuscleType { PRIMARY, SECONDARY, TERTIARY }

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
                "Seluruh tubuh tetap terlihat jelas di kamera selama gerakan.",
                "Tubuh menghadap samping serong ke kiri atau kanan kamera agar sudut squat terbaca lebih jelas.",
                "Punggung tetap lurus dan netral dari awal hingga akhir gerakan.",
                "Lutut sejajar dengan arah jari kaki dan tumit tetap menempel di lantai."
            ),
            wrongTechniques = listOf(
                "Tubuh terlalu menghadap depan atau terlalu membelakangi kamera sehingga sudut gerakan sulit terbaca.",
                "Sebagian tubuh keluar dari frame kamera saat mulai turun atau naik.",
                "Lutut kolaps ke dalam atau tumit terangkat dari lantai.",
                "Punggung membulat dan dada terlalu condong ke depan."
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
            importanceDesc = "Squat melatih lebih dari 200 otot sekaligus dan merupakan gerakan fungsional yang mensimulasikan aktivitas sehari-hari seperti duduk dan berdiri. Latihan ini terbukti meningkatkan kepadatan tulang dan metabolisme tubuh secara keseluruhan."
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
                "Seluruh tubuh tetap masuk frame agar posisi bahu, siku, dan lengan mudah dipantau.",
                "Tubuh menghadap samping kiri atau kanan kamera untuk menonjolkan lintasan siku.",
                "Siku tetap terkunci di sisi tubuh sepanjang gerakan.",
                "Gerakan naik dan turun dilakukan terkontrol tanpa melempar beban."
            ),
            wrongTechniques = listOf(
                "Tubuh menghadap depan kamera sehingga gerak siku tidak terbaca dengan baik.",
                "Sebagian tubuh terpotong dari frame saat melakukan curl.",
                "Mengayunkan tubuh ke belakang untuk membantu mengangkat beban.",
                "Siku bergerak maju-mundur dan beban diturunkan terlalu cepat."
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
            importanceDesc = "Bisep yang kuat mendukung semua gerakan tarikan seperti pull-up dan row. Melatih bisep secara teratur meningkatkan kekuatan fungsional dan penampilan lengan atas secara keseluruhan."
        ),
        "3" to ExerciseDetail(
            exerciseId = "3",
            definition = "Lateral Raise adalah gerakan isolasi yang menargetkan deltoid lateral atau bahu samping untuk menciptakan tampilan bahu yang lebar dan kuat. Gerakan ini melibatkan pengangkatan lengan ke samping hingga sejajar dengan bahu.",
            steps = listOf(
                "Pastikan seluruh tubuh terlihat jelas di kamera dari kepala sampai kaki.",
                "Posisikan tubuh menghadap depan ke kamera.",
                "Berdiri tegak dengan kaki selebar bahu dan pegang dumbbell di kedua sisi tubuh.",
                "Tekuk siku sedikit dan pertahankan sudutnya sepanjang gerakan.",
                "Angkat kedua lengan ke samping hingga sejajar dengan bahu, lalu turunkan kembali secara perlahan."
            ),
            correctTechniques = listOf(
                "Seluruh tubuh terlihat jelas di kamera sepanjang gerakan.",
                "Tubuh menghadap depan kamera agar pergerakan kedua lengan terlihat seimbang.",
                "Lengan bergerak ke samping, bukan ke depan.",
                "Bahu tetap rileks dan tidak ikut terangkat saat mengangkat beban."
            ),
            wrongTechniques = listOf(
                "Tubuh diputar ke samping sehingga kedua lengan tidak terbaca simetris.",
                "Sebagian tubuh keluar dari frame kamera.",
                "Mengayunkan tubuh atau menggunakan momentum saat mengangkat lengan.",
                "Mengangkat lengan melebihi tinggi bahu atau membiarkan bahu shrugging."
            ),
            tips = listOf(
                "Gunakan sudut kamera depan agar kanan dan kiri bisa dibandingkan dengan jelas.",
                "Pilih beban ringan dan prioritaskan kontrol gerakan penuh.",
                "Jaga jarak dengan kamera agar kedua tangan tetap terlihat dari awal sampai akhir."
            ),
            primaryMuscle = "Lateral Deltoids",
            primaryMuscleDesc = "Deltoid lateral adalah target utama yang membentuk lebar bahu",
            muscleGroups = listOf(
                MuscleGroup("Lateral Deltoid (bahu samping)", MuscleType.PRIMARY),
                MuscleGroup("Anterior Deltoid (bahu depan)", MuscleType.SECONDARY),
                MuscleGroup("Supraspinatus (rotator cuff)", MuscleType.SECONDARY),
                MuscleGroup("Trapezius Upper", MuscleType.TERTIARY)
            ),
            importanceDesc = "Deltoid lateral memberikan ilusi bahu yang lebar dan membentuk rasio pinggang-bahu yang ideal. Otot ini juga penting untuk stabilitas bahu dan performa gerakan overhead."
        ),
        "4" to ExerciseDetail(
            exerciseId = "4",
            definition = "Shoulder Press adalah gerakan compound yang melatih seluruh kompleks otot bahu dengan mendorong beban dari posisi bahu ke atas kepala. Gerakan ini merupakan salah satu indikator utama kekuatan tubuh bagian atas.",
            steps = listOf(
                "Pastikan seluruh tubuh terlihat jelas di kamera dari kepala sampai kaki.",
                "Posisikan tubuh menghadap ke samping kiri atau kanan kamera.",
                "Duduk atau berdiri dengan punggung lurus dan pegang dumbbell setinggi bahu.",
                "Telapak tangan menghadap ke depan dan siku membentuk sudut sekitar 90 derajat.",
                "Kencangkan core lalu dorong beban lurus ke atas hingga lengan hampir lurus penuh.",
                "Turunkan beban kembali ke posisi awal secara perlahan dan terkontrol."
            ),
            correctTechniques = listOf(
                "Seluruh tubuh tetap terlihat di kamera agar lintasan beban dan postur tubuh mudah dipantau.",
                "Tubuh menghadap samping kiri atau kanan kamera untuk memperjelas dorongan vertikal.",
                "Punggung tetap lurus dan tidak melengkung berlebihan saat mendorong beban.",
                "Gerakan dilakukan lurus ke atas dengan ritme yang stabil."
            ),
            wrongTechniques = listOf(
                "Tubuh menghadap depan kamera sehingga lintasan dorongan sulit dibaca.",
                "Sebagian tubuh keluar dari frame saat beban diangkat ke atas.",
                "Punggung terlalu melengkung atau kepala terlalu maju saat beban di atas.",
                "Menggunakan momentum kaki atau mendorong beban tidak lurus ke atas."
            ),
            tips = listOf(
                "Gunakan posisi kamera samping agar sudut siku dan lintasan beban terlihat lebih jelas.",
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
            importanceDesc = "Shoulder press adalah fondasi kekuatan tubuh bagian atas yang mendukung semua gerakan pushing seperti bench press dan push-up. Otot bahu yang kuat juga membantu melindungi sendi bahu yang sangat mobile."
        )
    )
}
