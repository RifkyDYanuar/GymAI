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
    val type: MuscleType  // PRIMARY, SECONDARY, TERTIARY
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
                "Berdiri tegak dengan kaki selebar bahu, jari kaki sedikit ke luar 15–30°",
                "Posisikan barbell di trapezius atas (jika pakai beban) atau tangan lurus ke depan",
                "Tarik napas dalam-dalam, kencangkan core dan dada tetap tegak",
                "Dorong pinggul ke belakang sambil menekuk lutut secara perlahan",
                "Turunkan tubuh hingga paha sejajar atau sedikit di bawah lantai",
                "Pastikan lutut mengikuti arah jari kaki, tidak kolaps ke dalam",
                "Hembuskan napas kuat sambil mendorong tumit ke lantai untuk bangkit",
                "Kembali ke posisi awal dengan pinggul dan lutut lurus penuh"
            ),
            correctTechniques = listOf(
                "Punggung tetap lurus dan netral dari awal hingga akhir gerakan",
                "Lutut sejajar dengan jari kaki, tidak melebihi ujung jari kaki secara ekstrem",
                "Tumit selalu menempel di lantai sepanjang gerakan"
            ),
            wrongTechniques = listOf(
                "Lutut kolaps ke dalam (knee valgus) — berisiko cedera sendi lutut",
                "Tumit terangkat dari lantai saat turun — menandakan fleksibilitas ankle kurang",
                "Punggung membulat (butt wink) di titik terbawah — membebani tulang belakang",
                "Dada terlalu condong ke depan — mengalihkan beban dari kaki ke punggung",
                "Tidak turun cukup dalam (parallel) — mengurangi efektivitas latihan",
                "Lutut melewati jari kaki secara ekstrem — tekanan berlebih pada sendi lutut"
            ),
            tips = listOf(
                "Pemanasan pergelangan kaki dan pinggul sangat dianjurkan sebelum squat",
                "Gunakan cermin atau rekam video untuk memantau form dari samping",
                "Mulai dengan bodyweight squat sebelum menambah beban barbell"
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
                "Berdiri tegak atau duduk dengan punggung lurus",
                "Pegang dumbbell di kedua tangan dengan telapak menghadap ke depan (supinasi)",
                "Kunci siku di sisi tubuh, jangan biarkan bergerak",
                "Tekuk siku dan angkat dumbbell ke arah bahu secara perlahan",
                "Kontraksikan bisep di puncak gerakan selama 1 detik",
                "Turunkan beban secara perlahan hingga lengan hampir lurus penuh"
            ),
            correctTechniques = listOf(
                "Siku tetap terkunci di sisi tubuh sepanjang gerakan",
                "Gerakan naik dan turun sama-sama terkontrol (tidak melempar beban)",
                "Lengan hampir lurus penuh saat beban diturunkan (range of motion penuh)"
            ),
            wrongTechniques = listOf(
                "Mengayunkan tubuh ke belakang untuk membantu mengangkat beban",
                "Siku bergerak maju-mundur sehingga delt anterior ikut bekerja",
                "Menurunkan beban terlalu cepat — kehilangan manfaat eccentric contraction",
                "Beban terlalu berat sehingga form tidak terjaga"
            ),
            tips = listOf(
                "Coba variasi hammer curl untuk melatih brachialis dan brachioradialis",
                "Lakukan gerakan di depan cermin untuk memantau posisi siku",
                "Fokus pada koneksi pikiran-otot (mind-muscle connection) untuk hasil optimal"
            ),
            primaryMuscle = "Biceps Brachii",
            primaryMuscleDesc = "Otot bisep adalah penggerak utama dalam gerakan curl",
            muscleGroups = listOf(
                MuscleGroup("Biceps Brachii (bisep)", MuscleType.PRIMARY),
                MuscleGroup("Brachialis (bawah bisep)", MuscleType.SECONDARY),
                MuscleGroup("Brachioradialis (lengan bawah)", MuscleType.SECONDARY),
                MuscleGroup("Forearm Flexors", MuscleType.TERTIARY)
            ),
            importanceDesc = "Bisep yang kuat mendukung semua gerakan tarikan (pulling) seperti pull-up dan row. Melatih bisep secara teratur meningkatkan kekuatan fungsional dan penampilan lengan atas secara keseluruhan."
        ),
        "3" to ExerciseDetail(
            exerciseId = "3",
            definition = "Lateral Raise adalah gerakan isolasi yang menargetkan deltoid lateral (bahu samping) untuk menciptakan tampilan bahu yang lebar dan kuat. Gerakan ini melibatkan pengangkatan lengan ke samping hingga sejajar dengan bahu.",
            steps = listOf(
                "Berdiri tegak dengan kaki selebar bahu, pegang dumbbell di kedua sisi",
                "Tekuk siku sedikit (sekitar 10-15°) dan pertahankan sepanjang gerakan",
                "Angkat kedua lengan ke samping secara bersamaan",
                "Hentikan ketika lengan sejajar dengan bahu (posisi T)",
                "Turunkan kembali beban secara perlahan dan terkontrol"
            ),
            correctTechniques = listOf(
                "Lengan naik dengan gerakan arc (melengkung) ke samping, bukan ke depan",
                "Bahu tetap rileks, tidak ikut naik (shrugging) saat mengangkat",
                "Jempol sedikit lebih rendah dari kelingking di puncak gerakan"
            ),
            wrongTechniques = listOf(
                "Mengayunkan tubuh atau menggunakan momentum",
                "Bahu naik (shrugging) yang mengaktifkan trapezius bukan deltoid lateral",
                "Mengangkat lengan melebihi tinggi bahu — risiko impingement",
                "Beban turun terlalu cepat tanpa kontrol eccentric"
            ),
            tips = listOf(
                "Gunakan beban ringan dengan form sempurna lebih baik dari beban berat dengan form buruk",
                "Cable lateral raise memberikan tension konstan yang lebih baik dari dumbbell",
                "Lakukan unilateral (satu sisi) untuk mengatasi imbalance otot"
            ),
            primaryMuscle = "Lateral Deltoids",
            primaryMuscleDesc = "Deltoid lateral adalah target utama yang membentuk lebar bahu",
            muscleGroups = listOf(
                MuscleGroup("Lateral Deltoid (bahu samping)", MuscleType.PRIMARY),
                MuscleGroup("Anterior Deltoid (bahu depan)", MuscleType.SECONDARY),
                MuscleGroup("Supraspinatus (rotator cuff)", MuscleType.SECONDARY),
                MuscleGroup("Trapezius Upper", MuscleType.TERTIARY)
            ),
            importanceDesc = "Deltoid lateral memberikan ilusi bahu yang lebar dan membentuk rasio pinggang-bahu yang ideal (V-taper). Otot ini kritis untuk stabilitas bahu dan performa semua gerakan overhead."
        ),
        "4" to ExerciseDetail(
            exerciseId = "4",
            definition = "Shoulder Press adalah gerakan compound yang melatih seluruh kompleks otot bahu dengan mendorong beban dari posisi bahu ke atas kepala. Gerakan ini merupakan salah satu indikator utama kekuatan tubuh bagian atas.",
            steps = listOf(
                "Duduk atau berdiri dengan punggung lurus, pegang dumbbell setinggi bahu",
                "Telapak tangan menghadap ke depan, siku membentuk sudut 90°",
                "Kencangkan core dan tarik napas sebelum mendorong",
                "Dorong beban lurus ke atas hingga lengan hampir lurus penuh",
                "Turunkan beban kembali ke posisi awal secara terkontrol",
                "Ulangi gerakan dengan ritme yang konsisten"
            ),
            correctTechniques = listOf(
                "Punggung lurus dan tidak melengkung (arch berlebih) saat mendorong",
                "Siku sedikit di depan tubuh (tidak terlalu ke belakang) di posisi awal",
                "Lockout penuh di atas — lengan lurus namun elbow tidak hyperextend"
            ),
            wrongTechniques = listOf(
                "Punggung terlalu melengkung (lumbar hyperextension) — risiko cedera punggung bawah",
                "Menggunakan momentum kaki (leg drive berlebih) seperti push press",
                "Siku flare ke luar secara ekstrem di posisi bawah",
                "Kepala terlalu maju (forward head posture) saat beban di atas"
            ),
            tips = listOf(
                "Arnold Press adalah variasi untuk melatih semua kepala deltoid",
                "Lakukan rotator cuff warm-up sebelum shoulder press berat",
                "Duduk di bangku dengan sandaran punggung untuk perlindungan spine lebih baik"
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
            importanceDesc = "Shoulder press adalah fondasi kekuatan tubuh bagian atas yang mendukung semua gerakan pushing (bench press, push-up). Otot bahu yang kuat melindungi sendi yang paling mobile dan rentan cedera di tubuh."
        )
    )
}
