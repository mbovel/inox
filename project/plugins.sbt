
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.2.24")

addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.0")

// avoids warning from sbt-git, see https://github.com/sbt/sbt-git#known-issues
libraryDependencies += "org.slf4j" % "slf4j-nop" % "1.7.21"
