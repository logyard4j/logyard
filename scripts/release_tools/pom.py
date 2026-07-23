from __future__ import annotations

from xml.etree import ElementTree

from .types import Dependency, Publication


POM_NAMESPACE = "http://maven.apache.org/POM/4.0.0"
XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance"

ElementTree.register_namespace("", POM_NAMESPACE)
ElementTree.register_namespace("xsi", XSI_NAMESPACE)


def generate_pom(publication: Publication) -> bytes:
    project = ElementTree.Element(
        _tag("project"),
        {f"{{{XSI_NAMESPACE}}}schemaLocation": f"{POM_NAMESPACE} https://maven.apache.org/xsd/maven-4.0.0.xsd"},
    )
    _element(project, "modelVersion", "4.0.0")
    _element(project, "groupId", publication.group_id)
    _element(project, "artifactId", publication.artifact_id)
    _element(project, "version", publication.version)
    _element(project, "packaging", publication.packaging)
    _element(project, "name", publication.metadata.name)
    _element(project, "description", publication.metadata.description)
    _element(project, "url", publication.metadata.url)
    _license(project, publication)
    _developer(project)
    _scm(project, publication)
    _issues(project, publication)
    _dependency_management(project, publication.managed_dependencies)
    _dependencies(project, publication.dependencies)
    ElementTree.indent(project, space="  ")
    return ElementTree.tostring(project, encoding="utf-8", xml_declaration=True) + b"\n"


def _license(project: ElementTree.Element, publication: Publication) -> None:
    if publication.metadata.license != "Apache-2.0":
        raise ValueError(f"unsupported publication license: {publication.metadata.license}")
    licenses = _element(project, "licenses")
    license_element = _element(licenses, "license")
    _element(license_element, "name", "Apache License, Version 2.0")
    _element(license_element, "url", "https://www.apache.org/licenses/LICENSE-2.0.txt")
    _element(license_element, "distribution", "repo")


def _developer(project: ElementTree.Element) -> None:
    developers = _element(project, "developers")
    developer = _element(developers, "developer")
    _element(developer, "id", "zsumz")
    _element(developer, "name", "zsumz")
    _element(developer, "email", "shawn@zsumz.com")
    _element(developer, "url", "https://github.com/zsumz")


def _scm(project: ElementTree.Element, publication: Publication) -> None:
    scm = _element(project, "scm")
    _element(scm, "connection", "scm:git:https://github.com/zsumz/logyard.git")
    _element(scm, "developerConnection", "scm:git:ssh://git@github.com/zsumz/logyard.git")
    _element(scm, "tag", "HEAD" if publication.version.endswith("-SNAPSHOT") else f"v{publication.version}")
    _element(scm, "url", publication.metadata.scm)


def _issues(project: ElementTree.Element, publication: Publication) -> None:
    issues = _element(project, "issueManagement")
    _element(issues, "system", "GitHub")
    _element(issues, "url", publication.metadata.issues)


def _dependency_management(project: ElementTree.Element, dependencies: tuple[Dependency, ...]) -> None:
    if not dependencies:
        return
    management = _element(project, "dependencyManagement")
    _dependency_list(_element(management, "dependencies"), dependencies)


def _dependencies(project: ElementTree.Element, dependencies: tuple[Dependency, ...]) -> None:
    if dependencies:
        _dependency_list(_element(project, "dependencies"), dependencies)


def _dependency_list(parent: ElementTree.Element, dependencies: tuple[Dependency, ...]) -> None:
    for dependency in dependencies:
        dependency_element = _element(parent, "dependency")
        _element(dependency_element, "groupId", dependency.group_id)
        _element(dependency_element, "artifactId", dependency.artifact_id)
        if dependency.version:
            _element(dependency_element, "version", dependency.version)
        if dependency.scope != "compile":
            _element(dependency_element, "scope", dependency.scope)
        if dependency.optional:
            _element(dependency_element, "optional", "true")
        if dependency.exclusions:
            exclusions = _element(dependency_element, "exclusions")
            for exclusion in dependency.exclusions:
                exclusion_element = _element(exclusions, "exclusion")
                _element(exclusion_element, "groupId", exclusion.group_id)
                _element(exclusion_element, "artifactId", exclusion.artifact_id)


def _element(parent: ElementTree.Element, name: str, value: str | None = None) -> ElementTree.Element:
    child = ElementTree.SubElement(parent, _tag(name))
    child.text = value
    return child


def _tag(name: str) -> str:
    return f"{{{POM_NAMESPACE}}}{name}"
