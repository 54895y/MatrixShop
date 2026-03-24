rootProject.name = "MatrixShop"

val matrixLibBuild = listOf(
    file("../MatrixLib"),
    file("../MatrixLib-main"),
    file("../../MatrixLib/Code")
).firstOrNull { it.exists() }
if (matrixLibBuild != null) {
    includeBuild(matrixLibBuild) {
        dependencySubstitution {
            substitute(module("com.y54895.matrixlib:matrixlib-api"))
                .using(project(":"))
        }
    }
}
