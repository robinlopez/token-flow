import com.intellij.icons.AllIcons
val fields = AllIcons::class.java.classes.flatMap { it.fields.toList() }
for (f in fields) {
    if (f.name.contains("Grid", ignoreCase = true) || f.name.contains("Card", ignoreCase = true) || f.name.contains("Tile", ignoreCase = true)) {
        println(f.declaringClass.simpleName + "." + f.name)
    }
}
