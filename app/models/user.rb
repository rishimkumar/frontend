class User
  include Mongoid::Document
  include Mongoid::Timestamps

  # Include default devise modules. Others available are: :token_authenticatable, :encryptable,
  # :confirmable, :lockable, :timeoutable, :recoverable,
  # :rememberable, :trackable, :validatable and :omniauthable
  devise :trackable, :database_authenticatable, :recoverable, :rememberable, :registerable

  field :name
  field :contact, :type => Boolean
  field :admin, :type => Boolean, :default => false
  field :github_access_token
  field :signup_channel
  field :signup_referer

  # For making the form nicer, we try to prefetch these from github. When
  # they're not available in time, we need a default.
  field :fetched_name, :default => ""
  field :fetched_email, :default => ""

  has_and_belongs_to_many :projects

  validates_presence_of :email
  validates_uniqueness_of :email, :case_sensitive => false

  attr_accessible :name, :contact, :email, :password, :password_confirmation

  field :sent_first_build_email, :default => false

  def known_email
    if email.include? "@"
      email
    else
      "!!<#{fetched_email}>!!"
    end
  end

  # TECHNICAL_DEBT: After manually updating the DB, remove the sign_up_date. We
  # don't have this field for all our users, so set it to around the time we
  # added a bunch. We can manually update them later.
  def signup_at
    created_at || Time.new(2011, 12, 14, 22, 02, 15)
  end


  # https://github.com/plataformatec/devise/wiki/How-To:-Allow-users-to-edit-their-account-without-providing-a-password
  # Guest users start with a blank password, but we ask them to update them
  # later. However, without this hack, they won't be able to validate their
  # blank password, and devise will throw a caniption.
  def update_with_password(params = {})
    update_attributes(params)
  end

  # True if there has been at least one build in every project the user owns
  def build_in_every_project?
    self.projects.all? {|p| p.latest_build}
  end
end
