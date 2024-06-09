print("Started Adding the Users.");
db = db.getSiblingDB("testDB");
db.createUser({
  user: "user",
  pwd: "password",
  roles: [{ role: "readWrite", db: "testDB" }],
});
print("End Adding the User Roles.");