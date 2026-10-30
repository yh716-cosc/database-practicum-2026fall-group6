package minidb.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EnglishCommandTranslatorTest {
    private final EnglishCommandTranslator translator = new EnglishCommandTranslator();

    @Test
    void translatesProjectionAndTable() {
        assertEquals("SELECT * FROM students;", translator.translate("show all from students"));
        assertEquals("SELECT name, age FROM students;",
                translator.translate("SHOW name, age FROM students"));
        assertEquals("SELECT students.age FROM students;",
                translator.translate("show students.age from students"));
    }

    @Test
    void translatesComparisonOperators() {
        assertEquals("SELECT name FROM students WHERE age = 18;",
                translator.translate("show name from students where age equals 18"));
        assertEquals("SELECT name FROM students WHERE age <> 18;",
                translator.translate("show name from students where age is not equal to 18"));
        assertEquals("SELECT name FROM students WHERE age < 18;",
                translator.translate("show name from students where age is less than 18"));
        assertEquals("SELECT name FROM students WHERE age <= 18;",
                translator.translate("show name from students where age is at most 18"));
        assertEquals("SELECT name FROM students WHERE age > 18;",
                translator.translate("show name from students where age is greater than 18"));
        assertEquals("SELECT name FROM students WHERE age >= 18;",
                translator.translate("show name from students where age is at least 18"));
    }

    @Test
    void translatesBooleanExpressionsAndNullChecks() {
        assertEquals("SELECT name FROM students WHERE age >= 18 AND NOT (score < 85);",
                translator.translate("show name from students where age is at least 18 and not (score is less than 85)"));
        assertEquals("SELECT name FROM students WHERE age IS NULL OR name IS NOT NULL;",
                translator.translate("show name from students where age is null or name is not null"));
    }

    @Test
    void preservesAndEscapesValues() {
        assertEquals("SELECT id FROM students WHERE name = 'O''Brien';",
                translator.translate("show id from students where name equals 'O''Brien'"));
        assertEquals("SELECT id FROM students WHERE age = -18;",
                translator.translate("show id from students where age equals -18"));
    }

    @Test
    void translatesAliasesJoinsSortingAndLimits() {
        assertEquals("SELECT s.name, e.course FROM students AS s INNER JOIN enrollments AS e "
                        + "ON s.id = e.student_id WHERE s.age >= 18 ORDER BY e.course DESC, s.name ASC LIMIT 10;",
                translator.translate("show s.name, e.course from students as s inner join enrollments as e "
                        + "on s.id equals e.student_id where s.age is at least 18 "
                        + "ordered by e.course descending, s.name ascending limited to 10 rows"));
        assertEquals("SELECT * FROM students LEFT JOIN enrollments ON students.id = enrollments.student_id;",
                translator.translate("show all from students left outer join enrollments "
                        + "on students.id is equal to enrollments.student_id"));
        assertEquals("SELECT name FROM students AS s RIGHT JOIN enrollments AS e "
                        + "ON s.id = e.student_id ORDER BY name ASC LIMIT 3;",
                translator.translate("show name from students s right join enrollments e "
                        + "on s.id equals e.student_id sort by name asc return at most 3 rows"));
    }

    @Test
    void rejectsUnsupportedCommands() {
        assertThrows(EnglishCommandException.class,
                () -> translator.translate("list all students"));
        assertThrows(EnglishCommandException.class,
                () -> translator.translate("show all students"));
        assertThrows(EnglishCommandException.class,
                () -> translator.translate("show name from students where age"));
    }
}
