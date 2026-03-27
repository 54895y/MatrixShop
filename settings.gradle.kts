rootProject.name = "MatrixShop"

val matrixLibModule = "com.y54895.matrixlib:matrixlib-api"
val matrixLibBuild = listOf(
    file("../MatrixLib"),
    file("../MatrixLib-main"),
    file("../../MatrixLib/Code")
).firstOrNull { it.exists() }
if (matrixLibBuild != null) {
    includeBuild(matrixLibBuild) {
        dependencySubstitution {
            substitute(module(matrixLibModule))
                .using(project(":"))
        }
    }
} else {
    sourceControl {
        gitRepository(uri("https://github.com/54895y/MatrixLib.git")) {
            producesModule(matrixLibModule)
        }
    }
}
