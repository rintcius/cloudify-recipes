Feature: local mysql instance
  It should be working in a cool fashion 

   Scenario Outline: Negatively Checking tables
    Given I have a MySQL server on localhost
        And I use the username travel
        And I use the password test
        And I use the database travel
    Then it should not have the table <tableName>
    
    Examples:
      |tableName|
      | cats    |
      | bats    |
      | hats    |
